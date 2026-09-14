package com.grassland.intelligence.orchestration;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 任务书 #101 C101-10：收养清扫——每 10s 扫描、批次 20，为「dispatching 但没有在岗 workflow」的视觉任务
 * 幂等补起同 ID 工作流（启动失败被吞的行 / worker 重启窗口）。 不重新创建父操作（requestId 唯一键仍在）。
 */
@Component
public class CreationVisualAdoptionSweeper {

	private static final Logger log = LoggerFactory.getLogger(CreationVisualAdoptionSweeper.class);
	private static final int BATCH = 20;

	private final DatabaseClient db;
	private final CreationVisualWorkflowStarter starter;
	private final com.grassland.intelligence.creationstudio.CreationStudioProperties properties;

	public CreationVisualAdoptionSweeper(DatabaseClient db, CreationVisualWorkflowStarter starter,
			com.grassland.intelligence.creationstudio.CreationStudioProperties properties) {
		this.db = db;
		this.starter = starter;
		this.properties = properties;
	}

	@Scheduled(fixedDelayString = "${creation.studio.visual-adopt-interval-ms:10000}")
	public void sweep() {
		if (!properties.isVisualWorkerEnabled()) {
			return;
		}
		runOnce().subscribeOn(Schedulers.boundedElastic()).subscribe(count -> {
			if (count > 0) {
				log.info("creation visual adopted metric=creation_visual_adopt adopted={}", count);
			}
		}, error -> log.warn("creation visual adoption sweep failed", error));
	}

	/** 返回本次补起的 workflow 数（IT 直接断言）。 */
	public Mono<Long> runOnce() {
		return pendingOperations().doOnNext(this::adopt).count();
	}

	private Flux<UUID> pendingOperations() {
		return db
				.sql("SELECT id FROM card_series_operation WHERE api_version = 2"
						+ " AND dispatch_state <> 'completed' AND updated_at < now() - INTERVAL '10 seconds'"
						+ " ORDER BY updated_at LIMIT " + BATCH)
				.map((row, metadata) -> row.get("id", UUID.class)).all();
	}

	private void adopt(UUID id) {
		// 同 ID 幂等补起：已在跑的吞 AlreadyStarted，死掉的在这里复活
		try {
			starter.start(id);
		} catch (RuntimeException error) {
			log.warn("creation visual adopt failed id={}", id, error);
		}
	}
}
