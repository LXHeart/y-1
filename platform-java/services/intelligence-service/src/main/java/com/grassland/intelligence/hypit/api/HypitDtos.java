package com.grassland.intelligence.hypit.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Hypit 公开 DTO（任务书 #107-1 C107-03 / K03/K07）。
 *
 * <p>
 * 与 contracts/hypit-api.v1.json、src/types/hypit.ts 三方同步：金额是十进制字符串（BigDecimal ↔
 * string），未知值显式 null，列表统一 items/nextCursor。
 */
public final class HypitDtos {

	private HypitDtos() {
	}

	public record Money(String amount, String currency, boolean estimated, String source, String unknownReason) {
		public static Money unknown(String currency, String source, String reason) {
			return new Money(null, currency, false, source, reason);
		}

		public static Money of(BigDecimal amount, String currency, boolean estimated, String source) {
			return new Money(amount == null ? null : amount.toPlainString(), currency, estimated, source, null);
		}
	}

	public record Capabilities(boolean enabled, String version, List<FeatureReadiness> features,
			List<Map<String, Object>> templates) {
	}

	public record FeatureReadiness(String id, boolean installed, boolean configured, boolean prepared, boolean ready,
			String reason, String action) {
	}

	public record Project(String id, String ownerAccountId, String title, String mode, String status, long revision,
			long version, String selectedRun, Map<String, Object> sourceContext, String createdAt, String updatedAt) {
	}

	public record Page<T>(List<T> items, String nextCursor) {
	}

	public record AcceptedJob(String jobId, String state, String resourceId) {
	}

	public record ProjectCreated(Project project, AcceptedJob job) {
	}

	public record Job(String id, String projectId, String kind, String state, String phase,
			Map<String, Object> progress, String checkpointSummary, String blockedReason, List<String> nextActions,
			Map<String, String> error, String createdAt, String updatedAt) {
	}

	public record Build(String id, String engineBuildId, String projectId, long revision, String planId, String runFile,
			String lifecycle, String outcome, List<Map<String, Object>> operations, Map<String, Object> receiptSummary,
			boolean resultReady, String archiveState, int outputCount, String createdAt, String finishedAt) {
	}

	public record BuildCreated(Build build, AcceptedJob job) {
	}

	public record Output(String id, String buildId, String name, String displayName, String kind, String typeRef,
			String mediaType, Long sizeBytes, Double durationSeconds, Map<String, Object> valueSummary,
			String archiveState, String mediaId, List<String> dependencies) {
	}

	public static Map<String, Object> success(Object data) {
		return Map.of("success", true, "data", data);
	}

	public static Map<String, Object> failure(String error, String code) {
		return Map.of("success", false, "error", error, "code", code);
	}

	/**
	 * SSE 事件 id（K09.3）：{@code <jobUuid>:<sequence>}；Last-Event-ID 解析同行。序号非法时返回
	 * null（调用方回退 snapshot+reset）。
	 */
	public static String eventId(String jobId, long sequence) {
		return jobId + ":" + sequence;
	}

	public static Long parseLastEventId(String header) {
		if (header == null || header.isBlank()) {
			return null;
		}
		int at = header.lastIndexOf(':');
		if (at <= 0 || at == header.length() - 1) {
			return null;
		}
		try {
			long sequence = Long.parseLong(header.substring(at + 1));
			return sequence < 0 ? null : sequence;
		} catch (NumberFormatException error) {
			return null;
		}
	}
}
