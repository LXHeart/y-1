package com.grassland.intelligence.orchestration;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * 任务书 #101 C101-10：视觉 activity 声明（薄壳——只读领域服务结果，外部副作用不自动重试）。
 */
@ActivityInterface
public interface CreationVisualActivities {

	/** 单轮推进；返回 true 表示全部子项终态（workflow 收口）。 */
	@ActivityMethod
	boolean advance(String operationId);
}
