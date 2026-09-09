package com.grassland.marketplace.commerce;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 任务书 #98 D98-02：触达事实（referral_touch）数据访问。
 *
 * <p>
 * 消费者经 rlid 进入购买页即落行（未登录 consumer_account_id 为 NULL）；下单请求本身也可作为
 * 触达事实（context=order，全程未登录链路的兜底）。重复触达各记一行，last-touch 判定取最新。
 */
@Component
public class ReferralTouchRepository {

	private final DatabaseClient db;

	public ReferralTouchRepository(DatabaseClient db) {
		this.db = db;
	}

	public record TouchRow(UUID id, String referralLinkId, UUID consumerAccountId, Instant touchedAt, String context) {
	}

	public Mono<TouchRow> insert(String referralLinkId, UUID consumerAccountId, String context) {
		// 未登录触达 consumer 为 NULL——必须 bindNull（裸 bind null 无法推断类型 → BadSqlGrammar）。
		org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec spec = db
				.sql("INSERT INTO referral_touch(id, referral_link_id, consumer_account_id, touched_at, context)"
						+ " VALUES (CAST(:id AS uuid), :link, CAST(:rec AS uuid), now(), :context)"
						+ " RETURNING id, referral_link_id, consumer_account_id, touched_at, context")
				.bind("id", UUID.randomUUID().toString()).bind("link", referralLinkId).bind("context", context);
		spec = consumerAccountId == null ? spec.bindNull("rec", UUID.class) : spec.bind("rec", consumerAccountId);
		return spec.map(ReferralTouchRepository::mapRow).one();
	}

	/** 该消费者最近一次触达（任意链接、不限窗口）——窗口判定与 last-touch 归因的判据行。 */
	public Mono<TouchRow> findLatestByConsumer(UUID consumerAccountId) {
		return db
				.sql("SELECT id, referral_link_id, consumer_account_id, touched_at, context FROM referral_touch"
						+ " WHERE consumer_account_id = :rec ORDER BY touched_at DESC LIMIT 1")
				.bind("rec", consumerAccountId).map(ReferralTouchRepository::mapRow).one();
	}

	public Flux<TouchRow> listByLink(String referralLinkId, int limit) {
		return db
				.sql("SELECT id, referral_link_id, consumer_account_id, touched_at, context FROM referral_touch"
						+ " WHERE referral_link_id = :link ORDER BY touched_at DESC LIMIT :lim")
				.bind("link", referralLinkId).bind("lim", Math.max(1, Math.min(limit, 200)))
				.map(ReferralTouchRepository::mapRow).all();
	}

	public Mono<Long> countByLink(String referralLinkId) {
		return db.sql("SELECT count(*) AS c FROM referral_touch WHERE referral_link_id = :link")
				.bind("link", referralLinkId).map(row -> row.get("c", Long.class)).one().defaultIfEmpty(0L);
	}

	private static TouchRow mapRow(Readable row) {
		return new TouchRow(row.get("id", UUID.class), row.get("referral_link_id", String.class),
				row.get("consumer_account_id", UUID.class), instant(row), row.get("context", String.class));
	}

	private static Instant instant(Readable row) {
		java.time.OffsetDateTime value = row.get("touched_at", java.time.OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
}
