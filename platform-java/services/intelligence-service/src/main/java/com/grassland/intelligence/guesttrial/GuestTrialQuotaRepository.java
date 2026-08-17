package com.grassland.intelligence.guesttrial;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 游客试用额度（R4）：{@code guest_trial_quota(gtid, capability, day, used)} 轻量表。
 *
 * <p>扣减用原子条件 {@code UPDATE ... WHERE used < :limit RETURNING}（镜像 finance credits 原子扣减范式）：
 * 并发超限的输家拿空结果（调用方按「内容已产出、下次拒绝」语义弃置，R6）。日界由调用方按北京时间计算。
 */
@Component
public class GuestTrialQuotaRepository {

    private final DatabaseClient db;

    public GuestTrialQuotaRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 已用次数（无行 = 0）。 */
    public Mono<Integer> used(UUID gtid, String capability, LocalDate day) {
        return db.sql("SELECT used FROM guest_trial_quota"
                        + " WHERE gtid = CAST(:gtid AS uuid) AND capability = :cap AND day = :day")
                .bind("gtid", gtid).bind("cap", capability).bind("day", day)
                .map(r -> r.get("used", Integer.class)).one()
                .defaultIfEmpty(0);
    }

    /**
     * 成功计次（R6）：单条 upsert-自增（INSERT 首计 1；冲突且未满则 used+1；已满 → 0 行 = empty）。
     * 原子语义与「先种行再条件 UPDATE」等价（upsert 行锁并发串行化），且避免两段链在 R2DBC 下的
     * 完成信号坑（实测 .then().flatMap 同连接二段式会静默丢 UPDATE）。超限/并发输家 → empty：
     * 不报错、不回滚已产出内容，仅下次拒绝。
     */
    public Mono<Integer> consume(UUID gtid, String capability, LocalDate day, int limit) {
        return db.sql("""
                        INSERT INTO guest_trial_quota(gtid, capability, day, used)
                        VALUES (CAST(:gtid AS uuid), :cap, :day, 1)
                        ON CONFLICT (gtid, capability, day) DO UPDATE
                          SET used = guest_trial_quota.used + 1
                          WHERE guest_trial_quota.used < :limit
                        RETURNING used
                        """)
                .bind("gtid", gtid).bind("cap", capability).bind("day", day).bind("limit", limit)
                .map(r -> r.get("used", Integer.class)).one();
    }
}
