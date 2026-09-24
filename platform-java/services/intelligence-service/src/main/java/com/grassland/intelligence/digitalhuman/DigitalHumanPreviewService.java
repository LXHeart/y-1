package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.ai.byok.ByokRoutingService;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanInvocationService.PreparedInvocation;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationStage;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationKind;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.PreviewState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.PreviewRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.UsageUnits;
import com.grassland.intelligence.digitalhuman.DigitalHumanTextPolicy.SpeechTextSegment;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 试听服务（任务书 #105D C105D-04 / 共享契约 K08.1、K13.1）：固定句、每分钟 3 次、内存 TTL 10 分钟。
 *
 * <p>
 * 试听固定文案「你好，我是你的数字人创作助手，很高兴和你一起创作。」；不挂 session；预检 invocation
 * （previewId=resourceId）走 C105D-01 经济链（video_tts/feature=null 平台资助、实际计量入
 * ai_run）； 每账号 UTC 日 30 分钟平台补贴上限按 preview invocation 聚合；重复试听按原 requestId
 * 查结果，不免费重调。 音频存 {@code dh:preview:{id}}（TTL 10 分钟易失 Redis）；GET 过期 410、不重调
 * provider。限流/补贴检查在 DB 事务外，事务内不等待网络。
 */
@Component
public class DigitalHumanPreviewService {

	private static final Logger logger = LoggerFactory.getLogger(DigitalHumanPreviewService.class);

	/** K13.1 固定试听句（不得接受任意 text）。 */
	public static final String PREVIEW_SENTENCE = "你好，我是你的数字人创作助手，很高兴和你一起创作。";
	/** K08.1：每账号每天 30 分钟（1800 秒）平台补贴上限。 */
	public static final long DAILY_SUBSIDY_SECONDS_CAP = 1800;
	/** TTS 每段预留上界（K08.1：TTS 每段预留 90 秒）。 */
	private static final int RESERVED_SECONDS = 90;
	private static final int PER_MINUTE_LIMIT = 3;
	private static final Duration AUDIO_TTL = Duration.ofMinutes(10);

	private final DigitalHumanPreviewRepository previews;
	private final DigitalHumanInvocationService invocations;
	private final DigitalHumanOperations operations;
	private final DigitalHumanAudioBridge audioBridge;
	private final ByokRoutingService routing;
	private final ReactiveStringRedisTemplate redis;
	private final TransactionalOperator transactions;

	public DigitalHumanPreviewService(DigitalHumanPreviewRepository previews, DigitalHumanInvocationService invocations,
			DigitalHumanOperations operations, DigitalHumanAudioBridge audioBridge, ByokRoutingService routing,
			ReactiveStringRedisTemplate redis, TransactionalOperator transactions) {
		this.previews = previews;
		this.invocations = invocations;
		this.operations = operations;
		this.audioBridge = audioBridge;
		this.routing = routing;
		this.redis = redis;
		this.transactions = transactions;
	}

	public record PreviewOutcome(String id, String state, Instant expiresAt, String errorCode) {
	}

	/** API27：创建试听（幂等：同 requestId 查原结果，不免费重调）。 */
	public Mono<PreviewOutcome> create(PersonalActor actor, UUID requestId, String voiceId, int catalogVersion) {
		if (voiceId == null || voiceId.isBlank()) {
			return Mono.error(new IntelligenceException(422, "dh_invalid_input", "voiceId 必填。"));
		}
		return checkRateLimit(actor.accountId()).then(checkSubsidyCap(actor.accountId()))
				.then(routing.resolvePlatform("video_tts")).flatMap(provider -> {
					String payloadHash = DigitalHumanOperations.canonicalHash(java.util.Map.of("voiceId", voiceId,
							"catalogVersion", catalogVersion, "sentence", PREVIEW_SENTENCE));
					Mono<Prepared> body = operations
							.reserve(actor, OperationKind.preview_create, requestId, payloadHash, null)
							.flatMap(operation -> {
								if (operation.resourceId() != null) {
									return previews.findById(actor.accountId(), UUID.fromString(operation.resourceId()))
											.map(row -> new Prepared(new PreviewOutcome(row.id(), row.state().name(),
													row.expiresAt(), row.errorCode()), null, null, null))
											.switchIfEmpty(Mono
													.error(new IntelligenceException(404, "dh_not_found", "试听不存在。")));
								}
								UUID previewId = UUID.randomUUID();
								Instant expiresAt = Instant.now().plus(AUDIO_TTL);
								return previews.insert(new PreviewRow(previewId.toString(), actor.accountId(), voiceId,
										catalogVersion, PreviewState.processing, expiresAt, null, null, 1, null, null))
										.flatMap(row -> invocations.reserve(actor, null, null, InvocationStage.preview,
												previewId, 0, provider, requestHash(PREVIEW_SENTENCE, voiceId),
												Instant.now().plus(Duration.ofSeconds(90)), 0, 0, RESERVED_SECONDS)
												.flatMap(invocation -> invocations
														.prepare(UUID.fromString(invocation.id()))
														.flatMap(prepared -> operations
																.attachResource(UUID.fromString(operation.id()),
																		previewId)
																.thenReturn(new Prepared(
																		new PreviewOutcome(previewId.toString(),
																				PreviewState.processing.name(),
																				expiresAt, null),
																		invocation.id(), prepared, voiceId)))));
							});
					return transactions.transactional(body).flatMap(this::dispatch);
				});
	}

	/** API28：状态（processing/ready/failed/expired；过期读作 expired）。 */
	public Mono<PreviewOutcome> get(PersonalActor actor, UUID previewId) {
		return previews.findById(actor.accountId(), previewId)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "试听不存在。")))
				.map(row -> expired(row)
						? new PreviewOutcome(row.id(), PreviewState.expired.name(), row.expiresAt(), row.errorCode())
						: new PreviewOutcome(row.id(), row.state().name(), row.expiresAt(), row.errorCode()));
	}

	/**
	 * API29：读音频（认证流）。Redis 丢失或过期 → 410（不重调 TTS）；返回 16k mono s16le WAV（44 字节头），
	 * 内容仍为内存易失，不写素材库。
	 */
	public Mono<byte[]> readAudio(PersonalActor actor, UUID previewId) {
		return previews.findById(actor.accountId(), previewId)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "试听不存在。"))).flatMap(row -> {
					if (row.state() != PreviewState.ready || expired(row)) {
						return Mono.error(new IntelligenceException(410, "dh_preview_expired", "试听已过期。"));
					}
					return redis.opsForValue().get("dh:preview:" + row.id())
							.switchIfEmpty(Mono.error(new IntelligenceException(410, "dh_preview_expired", "试听音频已过期。")))
							.map(pcm -> DigitalHumanAudioBridge.wavOf(pcm.getBytes()));
				});
	}

	// ---------- 私有 ----------

	private record Prepared(PreviewOutcome outcome, String invocationId, PreparedInvocation prepared, String voiceId) {
	}

	private boolean expired(PreviewRow row) {
		return row.state() == PreviewState.ready && row.expiresAt().isBefore(Instant.now());
	}

	private Mono<Void> checkRateLimit(String owner) {
		String minuteKey = "dh:preview:rl:" + owner + ":" + LocalDate.now(ZoneOffset.UTC) + ":"
				+ Instant.now().atZone(ZoneOffset.UTC).getMinute();
		return redis.opsForValue().increment(minuteKey)
				.flatMap(count -> count == 1
						? redis.expire(minuteKey, Duration.ofSeconds(60)).thenReturn(count)
						: Mono.just(count))
				.flatMap(count -> count > PER_MINUTE_LIMIT
						? Mono.error(new IntelligenceException(429, "dh_rate_limited", "试听太频繁，请稍后再试。"))
						: Mono.empty());
	}

	private Mono<Void> checkSubsidyCap(String owner) {
		Instant utcDayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
		return previews.previewSecondsToday(owner, utcDayStart)
				.flatMap(used -> used + RESERVED_SECONDS > DAILY_SUBSIDY_SECONDS_CAP
						? Mono.error(new IntelligenceException(402, "dh_subsidy_exhausted", "今日试听额度已用完。"))
						: Mono.empty());
	}

	/** 经济链成功后派发 TTS：PCM 入易失 Redis、ready、按实际样本秒结算；失败落 failed 行并走既有补偿。 */
	private Mono<PreviewOutcome> dispatch(Prepared prepared) {
		if (prepared.invocationId() == null) {
			// 幂等重放：只回读，不重调 provider。
			return Mono.just(prepared.outcome());
		}
		AtomicInteger pcmBytes = new AtomicInteger();
		return audioBridge
				.streamTts(prepared.prepared(), new SpeechTextSegment(0, PREVIEW_SENTENCE, 0), prepared.voiceId())
				.doOnNext(chunk -> pcmBytes.addAndGet(chunk.length))
				.collect(() -> new java.io.ByteArrayOutputStream(), (out, chunk) -> out.write(chunk, 0, chunk.length))
				.flatMap(buffer -> redis.opsForValue()
						.set("dh:preview:" + prepared.outcome().id(),
								java.util.Base64.getEncoder().encodeToString(buffer.toByteArray()), AUDIO_TTL)
						.then(previews.markState(UUID.fromString(prepared.outcome().id()), PreviewState.ready, null))
						.then(invocations.settleSuccess(UUID.fromString(prepared.invocationId()),
								prepared.prepared().context(),
								new UsageUnits(null, null, null,
										(long) DigitalHumanAudioBridge.pcmBytesToSeconds(pcmBytes.get(),
												DigitalHumanTtsClient.PCM_SAMPLE_RATE) * 1000,
										null, null, null, "confirmed")))
						.thenReturn(new PreviewOutcome(prepared.outcome().id(), PreviewState.ready.name(),
								prepared.outcome().expiresAt(), null)))
				.onErrorResume(error -> {
					logger.warn("dh preview tts failed: previewId={}", prepared.outcome().id(), error);
					return previews
							.markState(UUID.fromString(prepared.outcome().id()), PreviewState.failed,
									error instanceof IntelligenceException ie ? ie.code() : "dh_runtime_unavailable")
							.then(invocations.fail(UUID.fromString(prepared.invocationId()),
									prepared.prepared().context(), "preview tts failed"))
							.thenReturn(new PreviewOutcome(prepared.outcome().id(), PreviewState.failed.name(),
									prepared.outcome().expiresAt(), "dh_runtime_unavailable"));
				});
	}

	private static String requestHash(String sentence, String voiceId) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest((sentence + "|" + voiceId).getBytes(StandardCharsets.UTF_8)));
		} catch (Exception failure) {
			throw new IllegalStateException("SHA-256 不可用", failure);
		}
	}
}
