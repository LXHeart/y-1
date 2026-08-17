package com.grassland.intelligence.guesttrial;

import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 游客试用审计（R8）：{@code guest_trial_run} append-only——只存随机 gtid、能力名、IP 截断哈希、结果、时间；
 * 不存输入内容与生成产物（内存态即焚），不存原始 IP/UA。无任何个人数据。
 */
@Component
public class GuestTrialRunRepository {

    /** 审计终局。 */
    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_QUOTA_EXHAUSTED = "quota_exhausted";
    public static final String OUTCOME_RATE_LIMITED = "rate_limited";
    public static final String OUTCOME_PROVIDER_ERROR = "provider_error";

    private final DatabaseClient db;

    public GuestTrialRunRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Void> append(UUID gtid, String capability, String ipHash, String outcome) {
        return db.sql("""
                        INSERT INTO guest_trial_run(id, gtid, capability, ip_hash, outcome)
                        VALUES (CAST(:id AS uuid), CAST(:gtid AS uuid), :cap, :ipHash, :outcome)
                        """)
                .bind("id", UUID.randomUUID().toString())
                .bind("gtid", gtid).bind("cap", capability)
                .bind("ipHash", ipHash).bind("outcome", outcome)
                .then();
    }
}
