package com.grassland.intelligence.ai.run;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 同事务 bind 回调参数（任务书 #105D C105D-01 / 共享契约 K08.1）：仅无密钥财务句柄。
 *
 * <p>
 * 在 {@code createRunRecord} 之后、事务提交之前随 bindPrepared 回调传给数字人域，用于同事务写
 * {@code dh_invocation.ai_run_id / budget_snapshot}。刻意不传
 * {@code ExecutionContext}——后者含 尚未创建的 charge 与解密后的密钥字段，不允许进入持久化路径。
 */
public record RealtimePreparedBinding(UUID runId, UUID operationId, UUID budgetId, LocalDate reservationDate,
		int reservedTokens, int reservedCents, String priceTableVersion, String creditsCentsPolicyVersion,
		boolean chargeRequired) {
}
