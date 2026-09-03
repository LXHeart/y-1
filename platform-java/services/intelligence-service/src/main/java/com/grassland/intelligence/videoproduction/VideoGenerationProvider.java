package com.grassland.intelligence.videoproduction;

import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Provider port. Vendor DTOs and status names remain behind implementations. */
public interface VideoGenerationProvider {

    String id();

    Mono<ProviderResult> submit(ProviderCommand command);

    Mono<ProviderResult> poll(String providerTaskId, int requestedDurationSeconds);

    record ProviderCommand(
            UUID jobId, String model, String prompt, List<String> images,
            int durationSeconds, String aspectRatio) {}

	record ProviderResult(
			State state, String providerTaskId, Integer progress, String resultUrl,
			Integer durationSeconds, String errorCode, String errorMessage, byte[] resultBytes) {

		public enum State { QUEUED, PROCESSING, UNKNOWN, SUCCEEDED, FAILED }

		/** URL 形态结果（原生 xAI 临时链接等）：字节为 null，归档走下载路径。 */
		public ProviderResult(State state, String providerTaskId, Integer progress, String resultUrl,
				Integer durationSeconds, String errorCode, String errorMessage) {
			this(state, providerTaskId, progress, resultUrl, durationSeconds, errorCode, errorMessage, null);
		}

		/** 字节形态结果（new-api 中转等无 URL 渠道）：provider 持凭据取回成片，归档免下载直存。 */
		public static ProviderResult withBytes(String providerTaskId, Integer durationSeconds, byte[] bytes) {
			return new ProviderResult(State.SUCCEEDED, providerTaskId, 100, null, durationSeconds, null, null, bytes);
		}
	}
}
