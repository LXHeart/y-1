package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationStage;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 第三方渲染服务（任务书 #105D C105D-05 / 共享契约 K14.2～K14.3、§9.1）。
 *
 * <p>
 * render 在 session connecting 阶段创建一次
 * invocation（resourceId=sessionId、segmentIndex=0、 turnId=null），稳定经济键沿 C105D-01
 * 链路（digital_human_render/feature=null 平台补贴、实际计量入
 * ai_run）。协议适配<b>显式</b>：仅当控制面现行生效的 digital_human_render 配置存在、且其 provider/协议
 * 在已注册适配列表内才派发；缺配置/停用/未受信/无适配 → 409 dh_configuration_changed（K14：不经
 * env/renderer-profile/本地兜底）。真实第三方协议未核验接通前实际出站 REAL_NOT_RUN——本服务交付配置驱动的
 * 资格判定与经济键，不宣称通用适配完成。
 */
@Component
public class DigitalHumanRenderService {

	private final com.grassland.intelligence.ai.byok.ByokRoutingService routing;
	private final DigitalHumanInvocationService invocations;
	private final List<DigitalHumanRenderProvider> providers;

	public DigitalHumanRenderService(com.grassland.intelligence.ai.byok.ByokRoutingService routing,
			DigitalHumanInvocationService invocations, List<DigitalHumanRenderProvider> providers) {
		this.routing = routing;
		this.invocations = invocations;
		this.providers = providers == null ? List.of() : providers;
	}

	/** K14.2 INTERNAL08 的 render 响应（非 TTS NDJSON）。 */
	public record RenderConnection(String invocationId, String providerSessionRef, String mediaEndpoint,
			String connectionGrant, Instant expiresAt, Instant deadlineAt) {
	}

	public record RenderCommand(UUID invocationId, String accountId, String backendId, UUID avatarId,
			int avatarRevision, Instant businessExpiresAt) {
	}

	public record ControlCommand(UUID invocationId, String accountId, String sessionId, long leaseEpoch, String action,
			String turnId, Long turnEpoch) {
	}

	public record ControlOutcome(boolean accepted, String state) {
	}

	// ---------- #105F C105F-01 §9.1：第三方形象检测/准备/删除协议 ----------

	/** 形象准备命令：原图字节 + sha256（Java 已做本地解码/尺寸/格式检查后传入）。 */
	public record AvatarPrepareCommand(UUID avatarId, String accountId, byte[] sourceImage, String sourceSha256,
			int revision) {
	}

	/**
	 * 第三方检测结果：faceCount!=1 → dh_image_rejected（0 脸/多脸都拒绝，K09）；额外收费方案
	 * {@code extraCharge=true} → 不批准 customAvatar 能力（K14.1：首期仅服务内含、无独立费用）。
	 */
	public record AvatarPreparation(int faceCount, String providerResourceRef, List<String> compatibleBackendIds,
			boolean extraCharge) {
	}

	/** 远端删除结果（confirmed=false 时调用方按原键有界重试，不报假零）。 */
	public record AvatarDeletion(boolean confirmed, String state) {
	}

	/**
	 * #105G C105G-02 §9.1：远端形象资源状态。{@code UNKNOWN} 只能来自供应商查询不可用/结果不确定—— 注销核对按
	 * unknown 计残留（不计 0），迟到结果经账号墓碑核验后落库。
	 */
	public enum RemoteResourceState {
		ACTIVE, DELETED, UNKNOWN
	}

	/** 连接配置（来自控制面现行行 + 已注册协议适配）。 */
	public record ResolvedRender(ProviderResolution provider, DigitalHumanRenderProvider adapter) {
	}

	/**
	 * 解析当前 render 资格：控制面缺行/停用/无凭据/不健康 → 409 dh_configuration_changed；协议未注册适配 →
	 * 409（不猜兼容）。TC105D-05-04 的「配置缺失不假成功」由本方法保证。
	 */
	public Mono<ResolvedRender> resolve() {
		return routing.resolvePlatform("digital_human_render")
				.switchIfEmpty(Mono.error(new IntelligenceException(409, "dh_configuration_changed", "渲染服务未配置或已停用。")))
				.flatMap(provider -> {
					DigitalHumanRenderProvider adapter = providers.stream()
							.filter(candidate -> candidate.protocol().equals(provider.provider())).findFirst()
							.orElse(null);
					if (adapter == null) {
						return Mono.error(new IntelligenceException(409, "dh_configuration_changed", "渲染协议未获批准，暂不可用。"));
					}
					return Mono.just(new ResolvedRender(provider, adapter));
				});
	}

	/**
	 * session connecting 阶段创建整场 render invocation（幂等：同 session 经济键复用）；实际远端会话创建
	 * 在真实协议接通前明确 503（不 claimDispatch、不产生出站）。
	 */
	public Mono<RenderConnection> createSessionInvocation(PersonalActor actor, UUID sessionId, UUID requestId) {
		Objects.requireNonNull(actor, "actor 必填");
		return resolve()
				.flatMap(resolved -> invocations
						.reserve(actor, sessionId, null, InvocationStage.render, sessionId, 0, resolved.provider(),
								"render-" + sessionId, Instant.now().plusSeconds(600), 0, 0, 600)
						.flatMap(row -> invocations.prepare(UUID.fromString(row.id()))
								.map(prepared -> new RenderConnection(row.id(), null, null, null,
										prepared.deadlineAt().plusSeconds(540), prepared.deadlineAt()))))
				.flatMap(connection -> unavailableUntilVerified(connection));
	}

	/** INTERNAL14/15：真实远端协议未核验接通前明确不可用（§9.1：不假成功、不新建 run）。 */
	public Mono<ControlOutcome> control(ControlCommand command) {
		return Mono
				.error(new IntelligenceException(503, "dh_runtime_unavailable", "真实渲染协议未接通（REAL_NOT_RUN），控制面明确不可用。"));
	}

	public Mono<RenderConnection> renewConnectionGrant(UUID invocationId, String accountId, UUID sessionId,
			long leaseEpoch) {
		return Mono.error(
				new IntelligenceException(503, "dh_runtime_unavailable", "真实渲染协议未接通（REAL_NOT_RUN），媒体资格续签明确不可用。"));
	}

	// ---------- #105F C105F-01 §9.1：第三方形象检测/准备/删除编排 ----------

	/**
	 * 按 owner/冻结配置处理远端形象——解析现行 digital_human_render 配置后调用 已注册协议适配的检测/准备。0/多脸 → 422
	 * dh_image_rejected；额外收费 → 409 dh_configuration_changed （无额外计费才批准）；配置撤销/无适配 →
	 * 409。真实协议适配未注册前本方法即 409（REAL_NOT_RUN）。
	 */
	public Mono<AvatarPreparation> prepareAvatarFor(PersonalActor actor, AvatarPrepareCommand command) {
		Objects.requireNonNull(actor, "actor 必填");
		return resolve().flatMap(resolved -> Mono.fromCallable(() -> resolved.adapter().prepareAvatar(command))
				.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).flatMap(preparation -> {
					if (preparation.extraCharge()) {
						return Mono.error(
								new IntelligenceException(409, "dh_configuration_changed", "形象预处理服务需要额外计费，当前能力未批准。"));
					}
					if (preparation.faceCount() != 1) {
						return Mono.error(new IntelligenceException(422, "dh_image_rejected",
								"图片必须为单人肖像（检测到 " + preparation.faceCount() + " 张人脸）。"));
					}
					if (preparation.providerResourceRef() == null || preparation.providerResourceRef().isBlank()) {
						return Mono
								.error(new IntelligenceException(503, "dh_runtime_unavailable", "形象准备服务未返回资源句柄，明确失败。"));
					}
					return Mono.just(preparation);
				}));
	}

	/** §9.1：远端形象删除（按外部资源句柄；confirmed=false 由调用方按原键有界重试）。 */
	public Mono<AvatarDeletion> deleteRemoteAvatar(String providerResourceRef) {
		if (providerResourceRef == null || providerResourceRef.isBlank()) {
			// 无外部句柄（远端未建立）视作已确认删除——本地缓存仍逐句柄清理。
			return Mono.just(new AvatarDeletion(true, "absent"));
		}
		return resolve()
				.flatMap(resolved -> Mono.fromCallable(() -> resolved.adapter().deleteAvatar(providerResourceRef))
						.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()));
	}

	/**
	 * #105G C105G-02 §9.1：远端形象删除结果查询（未确认删除后的幂等恢复）。无外部句柄视作已删除； 配置缺失/协议未注册按
	 * {@link RemoteResourceState#UNKNOWN} 如实返回（不猜已删，不 409 冒充查询结论）。
	 */
	public Mono<RemoteResourceState> queryRemoteAvatar(String providerResourceRef) {
		if (providerResourceRef == null || providerResourceRef.isBlank()) {
			return Mono.just(RemoteResourceState.DELETED);
		}
		return resolve().map(resolved -> resolved.adapter().queryRemoteAvatar(providerResourceRef))
				.onErrorResume(error -> Mono.just(RemoteResourceState.UNKNOWN));
	}

	// ---------- 私有 ----------

	private Mono<RenderConnection> unavailableUntilVerified(RenderConnection connection) {
		return Mono.error(
				new IntelligenceException(503, "dh_runtime_unavailable", "真实渲染协议未接通（REAL_NOT_RUN），远端会话创建明确不可用。"));
	}

}
