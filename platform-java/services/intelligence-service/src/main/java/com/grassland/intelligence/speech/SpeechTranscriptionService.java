package com.grassland.intelligence.speech;

import com.grassland.intelligence.ai.ProviderInvocation;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.ai.run.PlatformConcurrencyLimiter;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.media.MediaChecksums;
import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public final class SpeechTranscriptionService {

	private static final Logger log = LoggerFactory.getLogger(SpeechTranscriptionService.class);
	private static final Set<String> LANGUAGES = Set.of("auto", "zh-CN", "en-US");
	private static final Set<String> MIME_TYPES = Set.of("audio/mpeg", "audio/mp4", "audio/wav", "audio/x-wav",
			"audio/webm", "audio/ogg");
	private static final long MAX_AUDIO_BYTES = 25L * 1024 * 1024;
	private static final long MAX_DURATION_MS = 900_000L;
	private static final Pattern STABLE_CODE = Pattern.compile("[a-z][a-z0-9_]{0,63}");

	private final IntelligenceCallerResolver callers;
	private final MediaReferenceRepository mediaReferences;
	private final SpeechTranscriptionRepository transcriptions;
	private final ObjectProvider<ObjectStorageAdapter> storageProvider;
	private final AudioDurationProbe durationProbe;
	private final AiExecutionService executions;
	private final PlatformConcurrencyLimiter concurrencyLimiter;
	private final SpeechProviderRegistry providers;
	private final TransactionalOperator transactions;

	@Autowired
	public SpeechTranscriptionService(IntelligenceCallerResolver callers, MediaReferenceRepository mediaReferences,
			SpeechTranscriptionRepository transcriptions, ObjectProvider<ObjectStorageAdapter> storageProvider,
			AudioDurationProbe durationProbe, AiExecutionService executions,
			PlatformConcurrencyLimiter concurrencyLimiter, SpeechProviderRegistry providers,
			TransactionalOperator intelligenceTransactionalOperator) {
		this.callers = callers;
		this.mediaReferences = mediaReferences;
		this.transcriptions = transcriptions;
		this.storageProvider = storageProvider;
		this.durationProbe = durationProbe;
		this.executions = executions;
		this.concurrencyLimiter = concurrencyLimiter;
		this.providers = providers;
		this.transactions = intelligenceTransactionalOperator;
	}

	public Mono<SpeechTranscription> create(ServerHttpRequest request, UUID mediaId, String requestedLanguage) {
		return callers.requireUser(request).flatMap(caller -> {
			String language = language(requestedLanguage);
			return requireOwnedMedia(mediaId, caller.accountId()).flatMap(this::readAndProbe).flatMap(
					audio -> createProcessing(caller.accountId(), caller.organizationId(), mediaId, language, audio));
		});
	}

	public Mono<SpeechTranscription> get(ServerHttpRequest request, UUID transcriptionId) {
		return callers.requireUser(request).flatMap(
				caller -> transcriptions.findOwned(transcriptionId, caller.accountId()).switchIfEmpty(notFound()));
	}

	private Mono<MediaReference> requireOwnedMedia(UUID mediaId, String accountId) {
		if (mediaId == null) {
			return Mono.error(new IllegalArgumentException("mediaId 不能为空"));
		}
		Instant now = Instant.now();
		return mediaReferences.findById(mediaId).filter(media -> accountId.equals(media.ownerAccountId()))
				.filter(media -> MediaPurpose.SPEECH_AUDIO.db().equals(media.purpose()))
				.filter(media -> media.status() == MediaStatus.ACTIVE && media.deletedAt() == null)
				.filter(media -> media.expiresAt() == null || media.expiresAt().isAfter(now))
				.filter(SpeechTranscriptionService::validMetadata).switchIfEmpty(notFound());
	}

	private Mono<AudioInput> readAndProbe(MediaReference media) {
		return Mono.fromCallable(() -> {
			ObjectStorageAdapter storage = storageProvider.getIfAvailable();
			if (storage == null) {
				throw new IntelligenceException(503, "speech_storage_unavailable", "语音音频存储暂不可用");
			}
			byte[] bytes;
			try {
				bytes = storage.getObject(media.objectKey());
			} catch (RuntimeException storageError) {
				// Object storage implementations may include the internal key in their
				// exception text.
				throw new SpeechStorageFailure();
			}
			if (bytes == null || bytes.length != media.sizeBytes() || bytes.length < 1 || bytes.length > MAX_AUDIO_BYTES
					|| !SpeechAudioPolicy.hasExpectedSignature(media.mimeType(), bytes)
					|| !media.checksum().equals(MediaChecksums.sha256(bytes))) {
				throw new IntelligenceException(404, "语音音频不存在");
			}
			long durationMs = durationProbe.probe(bytes);
			if (durationMs > MAX_DURATION_MS) {
				throw new IllegalArgumentException("语音音频时长不得超过 15 分钟");
			}
			return new AudioInput(bytes, durationMs, media.checksum(), media.mimeType());
		}).subscribeOn(Schedulers.boundedElastic()).onErrorMap(error -> sanitizeStorageError(error));
	}

	private Mono<SpeechTranscription> createProcessing(String accountId, String organizationId, UUID mediaId,
			String language, AudioInput audio) {
		UUID id = UUID.randomUUID();
		SpeechTranscription processing = new SpeechTranscription(id, mediaId, accountId, organizationId, language, null,
				audio.durationMs(), "processing", null, null, null, null, null, null, java.util.List.of(), null, null,
				null);
		return Mono
				.usingWhen(transcriptions.createProcessing(processing),
						transcription -> prepareAndExecute(transcription, audio, accountId, organizationId),
						ignored -> Mono.empty(),
						(transcription, error) -> finalizeTranscriptionFailure(transcription.id(), failureCode(error)),
						transcription -> finalizeTranscriptionFailure(transcription.id(), "execution_cancelled"))
				.onErrorMap(SpeechTranscriptionService::exposedError);
	}

	private Mono<SpeechTranscription> prepareAndExecute(SpeechTranscription transcription, AudioInput audio,
			String accountId, String organizationId) {
		return Mono.usingWhen(executions.prepareExecution(accountId, organizationId, "voice",
				CreditFeature.AI_RUN_VOICE, 0, 0, 0, billedSeconds(audio.durationMs()), true), prepared -> {
					if (!prepared.allowed()) {
						return Mono.error(deniedException(prepared.denialReason()));
					}
					return executePrepared(transcription, audio, prepared.context());
				}, ignored -> Mono.empty(),
				(prepared, error) -> finalizeRunFailure(transcription.id(), prepared, failureCode(error)),
				prepared -> finalizeRunFailure(transcription.id(), prepared, "execution_cancelled"));
	}

	private Mono<SpeechTranscription> executePrepared(SpeechTranscription transcription, AudioInput audio,
			AiExecutionService.ExecutionContext context) {
		return Mono.usingWhen(concurrencyLimiter.acquire(context.provider()), lease -> Mono.defer(() -> {
			// Keep provider selection errors (for example unsupported_provider) visible;
			// only the provider's own response/error boundary is sanitized.
			SpeechRecognitionProvider provider = providers.require(context.provider().provider());
			SpeechRecognitionProvider.Command command = new SpeechRecognitionProvider.Command(
					transcription.mediaReferenceId(), audio.checksum(), transcription.requestedLanguage(),
					audio.durationMs(), audio.bytes(), audio.mimeType(), invocation(context));
			return provider.transcribe(command).onErrorMap(SpeechTranscriptionService::sanitizeProviderError);
		}).flatMap(result -> complete(transcription, context, result)), PlatformConcurrencyLimiter.Lease::release,
				(lease, error) -> lease.release(), PlatformConcurrencyLimiter.Lease::release);
	}

	private Mono<SpeechTranscription> complete(SpeechTranscription transcription,
			AiExecutionService.ExecutionContext context, SpeechRecognitionProvider.Result result) {
		if (result == null || result.text() == null || result.text().isBlank() || result.inputTokens() < 0
				|| result.outputTokens() < 0 || result.billedSeconds() < 0) {
			return Mono.error(new IllegalStateException("语音模型返回无效结果"));
		}
		String detectedLanguage = result.detectedLanguage() == null || result.detectedLanguage().isBlank()
				? transcription.requestedLanguage()
				: result.detectedLanguage();
		int actualSeconds = Math.max(result.billedSeconds(), billedSeconds(transcription.durationMs()));
		Mono<SpeechTranscription> chain = requireChanged(transcriptions.storeProviderResult(transcription.id(),
				result.text(), detectedLanguage, context.provider().provider(), context.provider().model(),
				context.provider().platformModelVersion(), context.runId(), result.segments()), "语音转写结果保存失败")
				.then(requireChanged(executions.settleSuccess(context, result.inputTokens(), result.outputTokens(), 0,
						actualSeconds), "语音转写 Run 结算失败"))
				.then(requireChanged(transcriptions.markCompleted(transcription.id()), "语音转写完成状态保存失败"))
				.then(transcriptions.findOwned(transcription.id(), transcription.ownerAccountId())
						.switchIfEmpty(Mono.error(new IllegalStateException("语音转写完成结果不可读"))));
		return transactions.transactional(chain);
	}

	private Mono<Void> finalizeTranscriptionFailure(UUID transcriptionId, String failureCode) {
		return cleanupTranscription(transcriptionId, failureCode).onErrorResume(cleanupError -> Mono.empty());
	}

	private Mono<Void> cleanupTranscription(UUID transcriptionId, String failureCode) {
		return Mono
				.defer(() -> transactions
						.transactional(
								requireChanged(transcriptions.markFailed(transcriptionId, failureCode), "语音转写失败状态保存失败"))
						.then())
				.doOnError(error -> logFinalizationFailure(transcriptionId, null, "transcription_mark_failed", error));
	}

	private Mono<Void> finalizeRunFailure(UUID transcriptionId, AiExecutionService.ExecutionResult prepared,
			String failureCode) {
		if (!prepared.allowed()) {
			return Mono.empty();
		}
		return Mono.defer(() -> {
			AiExecutionService.ExecutionContext context = prepared.context();
			return requireChanged(executions.handleFailure(context, "speech transcription failed: " + failureCode),
					"语音转写 Run 失败状态保存失败").then()
					.doOnError(error -> logFinalizationFailure(transcriptionId, context.runId(), "run_mark_failed",
							error));
		}).onErrorResume(cleanupError -> Mono.empty());
	}

	private static void logFinalizationFailure(UUID transcriptionId, UUID runId, String failureStage, Throwable error) {
		String exceptionType = error == null || error.getClass().getSimpleName().isBlank()
				? "Unknown"
				: error.getClass().getSimpleName();
		log.warn(
				"speech failure finalization failed: transcriptionId={}, runId={}, "
						+ "failureStage={}, exceptionType={}, errorCategory=finalization_failed",
				transcriptionId, runId, failureStage, exceptionType);
	}

	private static Mono<Boolean> requireChanged(Mono<Boolean> change, String message) {
		return change.flatMap(changed -> changed ? Mono.just(true) : Mono.error(new IllegalStateException(message)));
	}

	private static String language(String value) {
		String normalized = value == null || value.isBlank() ? "auto" : value.trim();
		if (!LANGUAGES.contains(normalized)) {
			throw new IllegalArgumentException("language 仅支持 auto、zh-CN 或 en-US");
		}
		return normalized;
	}

	private static boolean validMetadata(MediaReference media) {
		return media.objectKey() != null && !media.objectKey().isBlank() && media.checksum() != null
				&& !media.checksum().isBlank() && media.sizeBytes() >= 1 && media.sizeBytes() <= MAX_AUDIO_BYTES
				&& media.mimeType() != null && MIME_TYPES.contains(media.mimeType().trim().toLowerCase(Locale.ROOT));
	}

	private static Throwable sanitizeStorageError(Throwable error) {
		if (error instanceof IntelligenceException || error instanceof IllegalArgumentException) {
			return error;
		}
		return new IntelligenceException(502, "speech_media_unavailable", "语音音频暂不可用");
	}

	private static Throwable sanitizeProviderError(Throwable error) {
		if (error instanceof IntelligenceException intelligence) {
			return switch (intelligence.code() == null ? "" : intelligence.code()) {
				case "provider_timeout" -> new IntelligenceException(504, "provider_timeout", "语音识别服务超时");
				case "provider_invalid_response" ->
					new IntelligenceException(502, "provider_invalid_response", "语音识别服务返回无效数据");
				case "provider_failure" -> new IntelligenceException(502, "provider_failure", "语音识别服务调用失败");
				default -> new SpeechProviderFailure();
			};
		}
		return new SpeechProviderFailure();
	}

	private static String failureCode(Throwable error) {
		if (error instanceof IntelligenceException intelligence) {
			if (intelligence.code() != null && STABLE_CODE.matcher(intelligence.code()).matches()) {
				return intelligence.code();
			}
			if (intelligence.status() == 429) {
				return "concurrency_unavailable";
			}
		}
		return "provider_failure";
	}

	private static IntelligenceException exposedError(Throwable error) {
		if (error instanceof IntelligenceException intelligence) {
			if (intelligence.status() == 429) {
				return new IntelligenceException(429, "concurrency_unavailable", "语音识别并发已满，请稍后重试");
			}
			return intelligence;
		}
		return new IntelligenceException(502, "speech_provider_failed", "语音识别服务调用失败");
	}

	private static IntelligenceException deniedException(String reason) {
		return switch (reason) {
			case "no_platform_model" -> new IntelligenceException(503, "no_platform_model", "平台未配置语音识别模型");
			case "unpriced_model" -> new IntelligenceException(503, "unpriced_model", "语音识别模型缺少价目配置");
			case "insufficient_credits", "exceeds_run_budget", "exceeds_daily_budget", "exceeds_monthly_budget" ->
				new IntelligenceException(402, reason, "已达语音识别预算上限");
			default -> new IntelligenceException(403, stableCode(reason, "execution_denied"), "语音识别执行被拒绝");
		};
	}

	private static String stableCode(String candidate, String fallback) {
		return candidate != null && STABLE_CODE.matcher(candidate).matches() ? candidate : fallback;
	}

	private ProviderInvocation invocation(AiExecutionService.ExecutionContext context) {
		if ("sandbox".equalsIgnoreCase(context.provider().provider())) {
			return null;
		}
		// 任务书 #58 决策 E：env bearer 兜底已删。执行环 prepare 已保证走到这里的非 Sandbox 平台
		// 解析必带凭据密钥（无密钥在 ProviderKeyDecryptor 即 503），bearer 只剩密文解密一条来路。
		String bearer = context.provider().needsKeyDecryption() ? context.decryptedKey() : null;
		try {
			return new ProviderInvocation(context.provider().provider(), context.provider().baseUrl(),
					context.provider().model(), bearer, context.provider().isByok());
		} catch (IllegalArgumentException error) {
			throw new IntelligenceException(503, "provider_credentials_missing", "语音识别 Provider 配置不完整");
		}
	}

	private static int billedSeconds(long durationMs) {
		return Math.toIntExact(Math.max(1L, (durationMs + 999L) / 1000L));
	}

	private static <T> Mono<T> notFound() {
		return Mono.error(new IntelligenceException(404, "语音音频不存在"));
	}

	private static final class SpeechProviderFailure extends RuntimeException {
		private SpeechProviderFailure() {
			super("speech provider failed");
		}
	}

	private static final class SpeechStorageFailure extends RuntimeException {
		private SpeechStorageFailure() {
			super("speech media unavailable");
		}
	}

	private record AudioInput(byte[] bytes, long durationMs, String checksum, String mimeType) {
	}
}
