package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.UsageUnits;
import com.grassland.intelligence.digitalhuman.DigitalHumanRenderService.AvatarDeletion;
import com.grassland.intelligence.digitalhuman.DigitalHumanRenderService.AvatarPreparation;
import com.grassland.intelligence.digitalhuman.DigitalHumanRenderService.AvatarPrepareCommand;
import com.grassland.intelligence.digitalhuman.DigitalHumanRenderService.ControlCommand;
import com.grassland.intelligence.digitalhuman.DigitalHumanRenderService.ControlOutcome;
import com.grassland.intelligence.digitalhuman.DigitalHumanRenderService.RemoteResourceState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRenderService.RenderCommand;
import com.grassland.intelligence.digitalhuman.DigitalHumanRenderService.RenderConnection;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * runtime 内置静态渲染档（opentalking mock 同形态：形象静态帧 + runtime 本地合成媒体）。
 *
 * <p>
 * 与真实第三方协议（K14.5 REAL_NOT_RUN 占位）不同，本档渲染发生在 dh-runtime 进程内（本地 形象资产 → WebRTC
 * 轨道），不存在外部供应商会话/凭据/出站调用：createSession 返回 runtime 内部引用（媒体仍走既有 audio WS + 内部
 * WebRTC 链，不经远端 endpoint/grant）；形象准备是 runtime 无模型规范化（解码/尺寸校验 + 本地 manifest，见
 * grassland_dh.avatar.prepare_avatar）， 不做远端人脸检测；用量恒零（本地合成不计量，经济链由 llm/stt stage
 * 独立承担）。 画面为静态帧 + 随音频能量的轻微律动——真实口型驱动（QuickTalk/Wav2Lip）仍需 GPU，不在本档。
 */
@Component
public class StaticRenderProvider implements DigitalHumanRenderProvider {

	/** 控制面 platform_model_config.provider 匹配值（配置行无凭据：本地合成不需要）。 */
	public static final String PROTOCOL = "runtime-static";

	@Override
	public String protocol() {
		return PROTOCOL;
	}

	@Override
	public RenderConnection createSession(RenderCommand command) {
		// 渲染在 runtime 进程内，无外部会话：mediaEndpoint/connectionGrant 留空（媒体走
		// 既有 audio WS 与内部 WebRTC offer 链）。providerSessionRef 以 runtime: 前缀标识本地档。
		Instant deadline = command.businessExpiresAt();
		return new RenderConnection(null, "runtime:" + command.invocationId(), null, null, deadline,
				deadline == null ? Instant.now().plusSeconds(600) : deadline);
	}

	@Override
	public ControlOutcome control(ControlCommand command) {
		// 打断/关闭由 runtime 会话状态机在内部链执行；控制面按接受回执（interrupt 带 turn 代次）。
		return new ControlOutcome(true, "accepted");
	}

	@Override
	public UsageUnits queryUsage(String providerSessionRef) {
		// 本地合成不计量（render stage 无计费，K08.1 creditFeatureFor 对 render 恒 null）。
		return new UsageUnits(0L, 0L, 0L, 0L, 0L, 0L, null, "static");
	}

	@Override
	public AvatarPreparation prepareAvatar(AvatarPrepareCommand command) {
		// runtime 无模型规范化（本地解码/尺寸校验）；上游 opentalking mock 档同理不做远端检测。
		// faceCount=1 维持「单人肖像」输入契约（UI 已提示）；资源句柄为本地 avatar 标识。
		return new AvatarPreparation(1, "local:" + command.avatarId() + ":" + command.revision(), List.of(PROTOCOL),
				false);
	}

	@Override
	public AvatarDeletion deleteAvatar(String providerResourceRef) {
		// 本地资产由 runtime 清理链（cleanup）负责；控制面侧删除即时确认。
		return new AvatarDeletion(true, "local");
	}

	@Override
	public RemoteResourceState queryRemoteAvatar(String providerResourceRef) {
		return RemoteResourceState.DELETED;
	}
}
