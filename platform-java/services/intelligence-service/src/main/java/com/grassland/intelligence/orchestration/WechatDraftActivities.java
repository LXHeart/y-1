package com.grassland.intelligence.orchestration;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * 任务书 #101 C101-21：公众号草稿同步领域动作声明。 唯一动作 advance——读库推进一步（副作用守卫全在
 * WechatDraftSyncService 与数据库标记）。
 */
@ActivityInterface
public interface WechatDraftActivities {

	/** 推进同步；返回 true=终态（workflow 收口）。 */
	@ActivityMethod
	boolean advance(String syncId);
}
