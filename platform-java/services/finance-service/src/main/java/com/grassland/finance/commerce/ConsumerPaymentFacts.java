package com.grassland.finance.commerce;

/**
 * 消费支付事实只读载体（任务书 #103 C103-06 / §6.3 facts 响应 data 形状的类型化入口）。
 * 保留持久行访问器语义；序列化由 service 组装 Map，此 record 供内部调用方强类型消费。
 */
public record ConsumerPaymentFacts(String orderRef, String organizationId,
		ConsumerPaymentRepository.Payment payment, java.util.List<ConsumerPaymentRepository.Refund> refunds,
		ConsumerPaymentRepository.Split split, java.util.List<ConsumerPaymentRepository.SplitAllocation> allocations) {
}
