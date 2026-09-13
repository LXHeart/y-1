package com.grassland.intelligence.orchestration;

import com.grassland.intelligence.creationstudio.wechat.WechatProperties;
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
 * 任务书 #101 C101-21：公众号草稿同步收养清扫——每 10s、批次 20，为「未收口且没有在岗 workflow」的同步幂等补起 同 ID
 * 工作流（启动失败被吞的行 / worker 重启窗口）。 不重新创建同步（requestId/快照唯一键仍在）。
 */
@Component
public class WechatDraftAdoptionSweeper {

	private static final Logger log = LoggerFactory.getLogger(WechatDraftAdoptionSweeper.class);
	private static final int BATCH = 20;

	private final DatabaseClient db;
	private final WechatDraftWorkflowStarter starter;
	private final WechatProperties properties;

	public WechatDraftAdoptionSweeper(DatabaseClient db, WechatDraftWorkflowStarter starter,
			WechatProperties properties) {
		this.db = db;
		this.starter = starter;
		this.properties = properties;
	}

	@Scheduled(fixedDelayString = "${creation.wechat.adopt-interval-ms:10000}")
	public void sweep() {
		if (!properties.isWritesEnabled() || !properties.isWorkerEnabled()) {
			return;
		}
		runOnce().subscribeOn(Schedulers.boundedElastic()).subscribe(count -> {
			if (count > 0) {
				log.info("wechat draft adopted metric=wechat_draft_adopt adopted={}", count);
			}
		}, error -> log.warn("wechat draft adoption sweep failed", error));
	}

	/** 返回本次补起的 workflow 数（IT 直接断言）。 */
	public Mono<Long> runOnce() {
		return pending().doOnNext(this::adopt).count();
	}

	private Flux<UUID> pending() {
		return db
				.sql("SELECT id FROM creation_wechat_draft_sync WHERE dispatch_state <> 'completed'"
						+ " AND state IN ('preparing','uploading','submitting','verifying')"
						+ " AND updated_at < now() - INTERVAL '10 seconds' ORDER BY updated_at LIMIT " + BATCH)
				.map((row, metadata) -> row.get("id", UUID.class)).all();
	}

	private void adopt(UUID id) {
		// 同 ID 幂等补起：已在跑的吞 AlreadyStarted，死掉的在这里复活
		try {
			starter.start(id);
		} catch (RuntimeException error) {
			log.warn("wechat draft adopt failed id={}", id, error);
		}
	}
}
