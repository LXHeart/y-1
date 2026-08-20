package com.grassland.messaging;

/**
 * 事件契约错误——envelope 形状不对、必填字段缺失、payload 无法规范化。
 *
 * <p>
 * 标记为<b>不可重试</b>（{@code addNotRetryableExceptions}）：坏数据重试多少次都一样， 直接进 DLT
 * 由人工/重放处理，不阻塞分区。
 */
public class EventContractException extends RuntimeException {

	public EventContractException(String message) {
		super(message);
	}

	public EventContractException(String message, Throwable cause) {
		super(message, cause);
	}
}
