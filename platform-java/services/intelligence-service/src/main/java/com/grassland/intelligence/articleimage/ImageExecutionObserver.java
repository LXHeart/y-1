package com.grassland.intelligence.articleimage;

import java.time.LocalDate;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-08（§6.6 运行拆分）：图像执行观察者。
 *
 * <ul>
 * <li>{@link #prepared(UUID)}——prepareMediaExecution 完成后、外部请求发送前持久化
 * runId／执行操作绑定；失败（抛错）则上游调用为 0（TC101-035）；</li>
 * <li>{@link #generated(UUID, UUID)}——原图按确定性 key 保存后持久化 mediaId（先于结算，
 * 崩溃恢复只重放结算，不再请求供应商）；</li>
 * <li>{@link #reserved(UUID, UUID, LocalDate, int)}——预算预留句柄持久化（default 忽略）， 沿用
 * V62 video_shot_audio 的恢复模式；非持久实现无需处理。</li>
 * </ul>
 *
 * <p>
 * observer 由视觉任务持久层实现，禁止变为任意回调脚本（§6.6）。
 */
public interface ImageExecutionObserver {

	/** run 绑定持久化；失败阻止外部请求（供应商调用数为 0）。 */
	Mono<Void> prepared(UUID runId);

	/** 原图已按确定性 key 保存；持久化 mediaId 后才进入结算。 */
	Mono<Void> generated(UUID runId, UUID mediaId);

	/** 预算预留句柄（budgetId 为 null 表示 BYOK 零成本——无需恢复结算）。 */
	default Mono<Void> reserved(UUID runId, UUID budgetId, LocalDate reservationDate, int reservedCents) {
		return Mono.empty();
	}
}
