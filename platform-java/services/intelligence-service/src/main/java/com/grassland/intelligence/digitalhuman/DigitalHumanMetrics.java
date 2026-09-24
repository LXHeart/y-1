package com.grassland.intelligence.digitalhuman;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 数字人指标（任务书 #105G C105G-05 / 共享契约 K10）。
 *
 * <p>
 * 标签限定
 * {@code phase}/{@code provider}/{@code code}/{@code result}：account/session/token/正文
 * 一律<b>不</b>作为标签——本类 API 刻意不提供此类参数，高基数与正文泄漏在形状上不可能 （tc105g_05_04
 * 有界基数回归）。provider 入参经白名单有界化（{@link #providerTag}），未知或 形态非法的自由字符串一律
 * {@code other}，绝不进入标签值。
 *
 * <p>
 * 覆盖 K10 列举的观测面：每阶段 P50/P95（{@link #recordStage}，percentile 直方）、首音
 * （phase=first_audio）、错误（phase+稳定错误码）、未知用量数、WS 拒绝、旧 epoch 丢弃、 runtime 租约过期、录制
 * failed/partial/overflow、结束/孤儿清理耗时（phase=cleanup_*）。
 */
@Component
public class DigitalHumanMetrics {

	/** 阶段域（固定）：render 含第三方渲染；cleanup_session/cleanup_orphan 对应结束/孤儿清理。 */
	public enum Phase {
		connect, stt, llm, tts, preview, render, first_audio, recording, cleanup_session, cleanup_orphan
	}

	/** 稳定错误码域（固定）：只收域内值；域外一律 other（见 {@link #codeTag}）。 */
	public enum StableCode {
		dh_state_conflict, dh_lease_stale, dh_grant_invalid, dh_grant_expired, dh_content_deleted, dh_runtime_unavailable, dh_configuration_changed, provider_failure, provider_timeout, aborted, overflow, other
	}

	/** 录制结果域（固定）：failed/partial/overflow 容量越限/正常完成。 */
	public enum RecordingResult {
		completed, partial, failed, overflow
	}

	/**
	 * provider 白名单（有界标签域）：现有控制面 provider 值集；域外（含任意自由字符串、 正文片段、密钥形态）一律
	 * {@link #PROVIDER_OTHER}，防高基数与正文标签泄漏。
	 */
	public static final Set<String> KNOWN_PROVIDERS = Set.of("sandbox", "minimax", "openai", "openai-completions",
			"openai-compatible", "xai", "seedance", "wan", "qwen", "fake");

	public static final String PROVIDER_OTHER = "other";

	/** 允许的标签键全集（tc105G-05-04 断言面；新增键=改代码，不接受运行期自由键）。 */
	public static final Set<String> ALLOWED_TAG_KEYS = Set.of("phase", "provider", "code", "result");

	private final MeterRegistry registry;
	private final EnumMap<Phase, Timer> stageTimers = new EnumMap<>(Phase.class);
	private final Counter unknownUsage;
	private final Counter wsRejected;
	private final Counter staleEpochDropped;
	private final Counter runtimeLeaseExpired;
	private final EnumMap<RecordingResult, Counter> recordingOutcomes = new EnumMap<>(RecordingResult.class);

	public DigitalHumanMetrics(MeterRegistry registry) {
		this.registry = registry;
		for (Phase phase : Phase.values()) {
			// P50/P95：percentile 由注册中心按直方估计（无 per-session 序列）。
			stageTimers.put(phase, Timer.builder("dh_stage_duration").tag("phase", phase.name())
					.description("数字人阶段时长（P50/P95）").publishPercentiles(0.5, 0.95).register(registry));
		}
		this.unknownUsage = Counter.builder("dh_unknown_usage_total").description("未知用量调用数").register(registry);
		this.wsRejected = Counter.builder("dh_ws_rejected_total").description("WS 连接拒绝数").register(registry);
		this.staleEpochDropped = Counter.builder("dh_stale_epoch_dropped_total").description("旧 epoch 事件丢弃数")
				.register(registry);
		this.runtimeLeaseExpired = Counter.builder("dh_runtime_lease_expired_total").description("runtime 租约过期数")
				.register(registry);
		for (RecordingResult result : RecordingResult.values()) {
			recordingOutcomes.put(result, Counter.builder("dh_recording_total").tag("result", result.name())
					.description("录制结果计数").register(registry));
		}
	}

	/** provider 白名单有界化：域外/非法形态 → other（正文与密钥片段绝不成为标签值）。 */
	public static String providerTag(String raw) {
		if (raw == null) {
			return PROVIDER_OTHER;
		}
		String value = raw.trim().toLowerCase();
		if (value.length() > 32
				|| !value.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '-' || ch == '_')) {
			return PROVIDER_OTHER;
		}
		return KNOWN_PROVIDERS.contains(value) ? value : PROVIDER_OTHER;
	}

	private static String codeTag(StableCode code) {
		return code == null ? StableCode.other.name() : code.name();
	}

	// ---------- 记录 API（无账号/会话/正文参数） ----------

	/** 阶段时长（P50/P95 面向全部 stage 定时器）。 */
	public void recordStage(Phase phase, Duration duration) {
		stageTimers.get(phase).record(duration);
	}

	/** 阶段时长（带 provider 维度；provider 经 {@link #providerTag} 有界化）。 */
	public void recordStage(Phase phase, String rawProvider, Duration duration) {
		Timer.builder("dh_stage_provider_duration").tag("phase", phase.name()).tag("provider", providerTag(rawProvider))
				.register(registry).record(duration);
	}

	/** 阶段错误：phase + 稳定错误码（双枚举域 → 基数构造期有界）。 */
	public void recordError(Phase phase, StableCode code) {
		Counter.builder("dh_stage_errors_total").tag("phase", phase.name()).tag("code", codeTag(code))
				.register(registry).increment();
	}

	public void recordUnknownUsage() {
		unknownUsage.increment();
	}

	public void recordWsRejected() {
		wsRejected.increment();
	}

	public void recordStaleEpochDropped() {
		staleEpochDropped.increment();
	}

	public void recordRuntimeLeaseExpired() {
		runtimeLeaseExpired.increment();
	}

	public void recordRecording(RecordingResult result) {
		recordingOutcomes.get(result).increment();
	}

	/** 组合观测（测试与调用方便捷面）：时长 + 错误码一次记录。 */
	public void recordStageOutcome(Phase phase, StableCode code, Duration duration) {
		recordStage(phase, duration);
		recordError(phase, code);
	}
}
