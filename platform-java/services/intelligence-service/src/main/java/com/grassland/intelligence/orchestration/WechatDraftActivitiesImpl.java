package com.grassland.intelligence.orchestration;

import com.grassland.intelligence.creationstudio.wechat.WechatDraftSyncService;
import com.grassland.intelligence.creationstudio.wechat.WechatProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

/**
 * 任务书 #101 C101-21：activity 薄壳——调用领域服务 {@link WechatDraftSyncService#advance}；
 * 写开关/worker 开关关闭时直接视为无可推进（行留待重开或收养清扫补起）。
 */
@Component
@io.temporal.spring.boot.ActivityImpl(workers = WechatDraftWorkflowImpl.TASK_QUEUE)
public class WechatDraftActivitiesImpl implements WechatDraftActivities {

	private final WechatDraftSyncService syncs;
	private final WechatProperties properties;

	@Autowired
	public WechatDraftActivitiesImpl(WechatDraftSyncService syncs, WechatProperties properties) {
		this.syncs = syncs;
		this.properties = properties;
	}

	@Override
	public boolean advance(String syncId) {
		if (!properties.isWritesEnabled() || !properties.isWorkerEnabled()) {
			// 开关关闭：不推进（排空/重开语义；收养清扫在重开后补起）
			return false;
		}
		try {
			return Boolean.TRUE.equals(syncs.advance(java.util.UUID.fromString(syncId))
					.subscribeOn(Schedulers.boundedElastic()).block(java.time.Duration.ofMinutes(10)));
		} catch (RuntimeException error) {
			throw new IllegalStateException("wechat draft advance failed: " + error.getMessage(), error);
		}
	}
}
