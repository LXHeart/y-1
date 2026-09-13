package com.grassland.intelligence.orchestration;

import com.grassland.intelligence.creationstudio.CreationStudioProperties;
import com.grassland.intelligence.creationstudio.visual.VisualJobService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

/**
 * 任务书 #101 C101-10：activity 薄壳——调用领域服务 {@link VisualJobService#advance}；
 * 写开关/worker 开关关闭时直接视为无可推进（行留待重开或收养清扫）。
 */
@Component
@io.temporal.spring.boot.ActivityImpl(workers = CreationVisualWorkflowImpl.TASK_QUEUE)
public class CreationVisualActivitiesImpl implements CreationVisualActivities {

	private final VisualJobService jobs;
	private final CreationStudioProperties properties;

	@Autowired
	public CreationVisualActivitiesImpl(VisualJobService jobs, CreationStudioProperties properties) {
		this.jobs = jobs;
		this.properties = properties;
	}

	@Override
	public boolean advance(String operationId) {
		if (!properties.isWritesEnabled() || !properties.isVisualWorkerEnabled()) {
			// 开关关闭：不领新 item；返回 false 由 workflow 继续等待（排空/重开语义）
			return false;
		}
		try {
			return Boolean.TRUE.equals(jobs.advance(java.util.UUID.fromString(operationId))
					.subscribeOn(Schedulers.boundedElastic()).block(java.time.Duration.ofMinutes(30)));
		} catch (RuntimeException error) {
			throw new IllegalStateException("visual advance failed: " + error.getMessage(), error);
		}
	}
}
