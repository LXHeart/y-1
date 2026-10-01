package com.grassland.intelligence.hypit.client;

/**
 * sidecar 不可达（连接超时/拒绝等传输层失败）——结果未知、无副作用发生。
 *
 * C107F2-38（round-17 实录）：非变更工具（workspace.read 等）此前把这类瞬态 不可达当
 * {@code hypit_tool_failed} 终判，一次 30s 连接超时就把 job 打死；
 * 读操作无副作用，属暂缓类（{@code hypit_broker_unreachable}），交给 defer 重试语义收敛。继承
 * IllegalStateException：既有捕获方行为不变。
 */
public class HypitSidecarUnreachableException extends IllegalStateException {

	public HypitSidecarUnreachableException(String message, Throwable cause) {
		super(message, cause);
	}
}
