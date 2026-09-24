package com.grassland.intelligence.digitalhuman;

/**
 * 第三方渲染适配接口（任务书 #105D C105D-05 / 共享契约 K14.2、§9.1）。
 *
 * <p>
 * 显式协议选择——不做「任意云已兼容」假设：每个实现绑定一个已核验的供应商协议；支持列表显式声明，未知协议
 * 拒绝。真实协议细节仅依据所配置供应商的已核验合同；未接通前保留 REAL_NOT_RUN（K14.5）。
 */
public interface DigitalHumanRenderProvider {

	/** 协议标识（显式注册，不猜测兼容）。 */
	String protocol();

	/**
	 * 创建远端渲染会话：以<b>冻结</b>控制面凭据调用第三方；返回供应商会话引用（媒体端点 + 短期媒体资格由 供应商受控响应给出，经受信
	 * origin/DNS pinning/重定向校验）。
	 */
	DigitalHumanRenderService.RenderConnection createSession(DigitalHumanRenderService.RenderCommand command);

	/** 幂等取消/关闭远端会话（interrupt 必须带目标 turn 代次；close 为 null）。 */
	DigitalHumanRenderService.ControlOutcome control(DigitalHumanRenderService.ControlCommand command);

	/** 按供应商会话引用查询真实用量（不采信 runtime 自报金额/帧数）。 */
	DigitalHumanRecords.UsageUnits queryUsage(String providerSessionRef);

	/**
	 * K14.3 第三方形象检测/准备（#105F C105F-01 §9.1）：人脸检测与形象准备只用已冻结控制面配置的 第三方服务；返回外部资源句柄与兼容
	 * backend。无额外计费才批准——额外收费方案不在本协议内。
	 */
	DigitalHumanRenderService.AvatarPreparation prepareAvatar(DigitalHumanRenderService.AvatarPrepareCommand command);

	/** K14.3 远端形象删除（确认语义；超时调用方按原键查询，不报假零）。 */
	DigitalHumanRenderService.AvatarDeletion deleteAvatar(String providerResourceRef);

	/**
	 * #105G C105G-02 §9.1：远端形象删除结果查询（未删除/已删除/未知）。适配协议未提供查询能力时如实 返回
	 * {@code UNKNOWN}——调用方按原键有界重试，不得把 unknown 猜成已删除。
	 */
	default DigitalHumanRenderService.RemoteResourceState queryRemoteAvatar(String providerResourceRef) {
		return DigitalHumanRenderService.RemoteResourceState.UNKNOWN;
	}
}
