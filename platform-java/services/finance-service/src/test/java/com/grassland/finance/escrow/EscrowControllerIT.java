package com.grassland.finance.escrow;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.finance.FinanceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * escrow 端到端（草场 Epic 4 Slice 4E）。继承 {@link FinanceItSupport}。
 *
 * <p>覆盖：credit（余额递增/未开户404/别家org403）、reserve（成功扣余额+幂等/余额不足409）、
 * release（还原余额/非reserved409/别家org403/未知404），均验 outbox 事件。
 */
class EscrowControllerIT extends FinanceItSupport {

    @Test
    void creditIncrementsBalanceAndEvent() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        provision(merchant, org);
        client().post().uri("/api/finance/accounts/" + org + "/credit")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("amountCents", 1000))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.balanceCents").isEqualTo(1000);
        assertThat(balanceOf(org)).isEqualTo(1000L);
        assertThat(outboxCount("AccountCredited", org)).isEqualTo(1);
        // Slice 12 Stage 3：payload 里的用户账号（非 ledger accountId）才是通知收件人。
        assertThat(outboxPayloadField("AccountCredited", org, "payeeAccountId")).isEqualTo(merchant);
    }

    @Test
    void creditWithoutProvisionNotFound() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        client().post().uri("/api/finance/accounts/" + org + "/credit")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("amountCents", 100))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void creditOtherOrgForbidden() {
        String merchant = UUID.randomUUID().toString();
        String ownOrg = UUID.randomUUID().toString();
        provision(merchant, ownOrg);
        client().post().uri("/api/finance/accounts/" + UUID.randomUUID() + "/credit")
                .header("X-Grassland-Identity", sign(merchant, "merchant", ownOrg, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("amountCents", 100))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void reserveDecrementBalanceAndIdempotent() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1000);

        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", ref, "amountCents", 600))
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.data.status").isEqualTo("reserved")
                .jsonPath("$.data.amountCents").isEqualTo(600);
        assertThat(balanceOf(org)).isEqualTo(400L);
        assertThat(outboxCount("FundsReserved", org)).isEqualTo(1);

        // 幂等：同 ref 再 reserve → 200 既有，余额不再扣
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", ref, "amountCents", 600))
                .exchange().expectStatus().isOk();
        assertThat(balanceOf(org)).isEqualTo(400L);
        assertThat(outboxCount("FundsReserved", org)).isEqualTo(1);  // 不再写事件
    }

    @Test
    void reserveInsufficientFundsConflict() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        provision(merchant, org);
        credit(merchant, org, 500);
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", "eng-" + UUID.randomUUID(), "amountCents", 600))
                .exchange().expectStatus().isEqualTo(409);
        assertThat(balanceOf(org)).isEqualTo(500L);  // 未扣
    }

    @Test
    void releaseRestoresBalance() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1000);
        reserve(merchant, org, ref, 600);
        assertThat(balanceOf(org)).isEqualTo(400L);

        client().post().uri("/api/finance/reservations/" + ref + "/release")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("released");
        assertThat(balanceOf(org)).isEqualTo(1000L);  // 还原
        assertThat(outboxCount("FundsReleased", org)).isEqualTo(1);

        // 再 release → 409（已处理）
        client().post().uri("/api/finance/reservations/" + ref + "/release")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void releaseOtherOrgForbidden() {
        String merchant = UUID.randomUUID().toString();
        String ownOrg = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, ownOrg);
        credit(merchant, ownOrg, 1000);
        reserve(merchant, ownOrg, ref, 600);
        // 别家 org 的 merchant 试图释放
        client().post().uri("/api/finance/reservations/" + ref + "/release")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant", UUID.randomUUID().toString(), "basic_publish"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void releaseUnknownNotFound() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        provision(merchant, org);
        client().post().uri("/api/finance/reservations/eng-missing/release")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void reserveWithoutAssertionUnauthorized() {
        String org = UUID.randomUUID().toString();
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", "x", "amountCents", 100))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void reserveByMarketplaceServiceSucceeds() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1000);

        // marketplace Saga 服务断言 reserve（HLD 11.1 服务身份）
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", ref, "amountCents", 600))
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.data.status").isEqualTo("reserved");
        assertThat(balanceOf(org)).isEqualTo(400L);
        assertThat(outboxCount("FundsReserved", org)).isEqualTo(1);

        // 服务断言同样享受 engagement_ref 幂等
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", ref, "amountCents", 600))
                .exchange().expectStatus().isOk();
        assertThat(balanceOf(org)).isEqualTo(400L);
    }

    @Test
    void reserveByWrongServicePrincipalForbidden() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        provision(merchant, org);
        credit(merchant, org, 1000);
        // 未受信服务签发方在验签阶段拒绝 → 401
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", signService(org, "imposter"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", "eng-x", "amountCents", 100))
                .exchange().expectStatus().isUnauthorized();
        // 已受信但无该端点权限的 trust 服务 → 403
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", signService(org, "trust"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", "eng-trust", "amountCents", 100))
                .exchange().expectStatus().isForbidden();
        // org 不符的 marketplace 服务断言 → 403
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", signService(UUID.randomUUID().toString(), "marketplace"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", "eng-y", "amountCents", 100))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void releaseByMarketplaceServiceRestoresBalance() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1000);
        reserve(merchant, org, ref, 600);
        assertThat(balanceOf(org)).isEqualTo(400L);

        // marketplace Saga 服务断言 release（compensation 退还）
        client().post().uri("/api/finance/reservations/" + ref + "/release")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("released");
        assertThat(balanceOf(org)).isEqualTo(1000L);
    }

    // ---------- capture（Slice 5A） ----------

    @Test
    void captureFlipsStatusWithoutBalanceChange() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1000);
        reserve(merchant, org, ref, 600);
        assertThat(balanceOf(org)).isEqualTo(400L);  // reserve 扣 600

        client().post().uri("/api/finance/reservations/" + ref + "/capture")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("captured");
        assertThat(balanceOf(org)).isEqualTo(400L);  // capture 无余额变动
        assertThat(outboxCount("FundsCaptured", org)).isEqualTo(1);
    }

    @Test
    void captureNonReservedConflict() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1000);
        reserve(merchant, org, ref, 600);
        client().post().uri("/api/finance/reservations/" + ref + "/release")  // 先 release
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isOk();
        client().post().uri("/api/finance/reservations/" + ref + "/capture")  // 再 capture → 409（非 reserved）
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isEqualTo(409);
        assertThat(outboxCount("FundsCaptured", org)).isZero();  // 不发结算事件（TC90-002）
        assertThat(outboxCount("SplitCompleted", org)).isZero();
    }

    @Test
    void captureAlreadyCapturedConflict() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1000);
        reserve(merchant, org, ref, 600);
        client().post().uri("/api/finance/reservations/" + ref + "/capture")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isOk();
        client().post().uri("/api/finance/reservations/" + ref + "/capture")  // 再 capture → 409（已 captured）
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void captureByMarketplaceServiceSucceeds() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1000);
        reserve(merchant, org, ref, 600);
        client().post().uri("/api/finance/reservations/" + ref + "/capture")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("captured");
        assertThat(balanceOf(org)).isEqualTo(400L);  // 无余额变动
    }

    @Test
    void captureOtherOrgForbidden() {
        String merchant = UUID.randomUUID().toString();
        String ownOrg = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, ownOrg);
        credit(merchant, ownOrg, 1000);
        reserve(merchant, ownOrg, ref, 600);
        client().post().uri("/api/finance/reservations/" + ref + "/capture")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant", UUID.randomUUID().toString(), "basic_publish"))
                .exchange().expectStatus().isForbidden();
    }

    // ---------- D-05 单笔交易上限 ----------

    @Test
    void merchantReserveOverTxCapConflict() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        provision(merchant, org);
        credit(merchant, org, 20_000_000L);  // 余额充足，但超单笔上限
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", "eng-" + UUID.randomUUID(), "amountCents", 10_000_001L))
                .exchange().expectStatus().isEqualTo(409);
        assertThat(balanceOf(org)).isEqualTo(20_000_000L);  // 未扣
    }

    @Test
    void serviceAssertionExemptFromTxCap() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        provision(merchant, org);
        credit(merchant, org, 20_000_000L);
        // marketplace Saga 服务断言 permissionTier=null，豁免 D-05 单笔上限（金额已在发布时校验）；
        // 若误按 null→DRAFT(0) 执行，4F AcceptApplicationReservationWorkflow 会被拦死。
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", "eng-" + UUID.randomUUID(), "amountCents", 10_000_001L))
                .exchange().expectStatus().isCreated();
    }

    // ---------- reverse（Slice 6C Phase D / D-06）----------

    @Test
    void reverseRefundsCapturedAndRestoresBalance() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1000);
        reserve(merchant, org, ref, 600);
        capture(merchant, org, ref);  // reserved→captured（余额仍 400）
        assertThat(balanceOf(org)).isEqualTo(400L);

        // trust 服务断言 reverse（D-06：captured→refunded + 还原余额）
        client().post().uri("/api/finance/reservations/" + ref + "/reverse")
                .header("X-Grassland-Identity", signService(org, "trust"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("refunded");
        assertThat(balanceOf(org)).isEqualTo(1000L);  // 还原
        assertThat(outboxCount("FundsReversed", org)).isEqualTo(1);

        // 再 reverse → 409（已 refunded）
        client().post().uri("/api/finance/reservations/" + ref + "/reverse")
                .header("X-Grassland-Identity", signService(org, "trust"))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void reverseRejectsNonCaptured() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1000);
        reserve(merchant, org, ref, 600);  // reserved（未 capture）
        client().post().uri("/api/finance/reservations/" + ref + "/reverse")
                .header("X-Grassland-Identity", signService(org, "trust"))
                .exchange().expectStatus().isEqualTo(409);  // 须 captured
        assertThat(balanceOf(org)).isEqualTo(400L);  // 未动
    }

    @Test
    void reverseOnlyTrustServiceAllowed() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1000);
        reserve(merchant, org, ref, 600);
        capture(merchant, org, ref);
        // 同组织商户也不可绕过争议终局直接冲正 captured 资金。
        client().post().uri("/api/finance/reservations/" + ref + "/reverse")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isForbidden();
        // marketplace 服务断言不可 reverse（仅 trust）→ 403
        client().post().uri("/api/finance/reservations/" + ref + "/reverse")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isForbidden();
        assertThat(balanceOf(org)).isEqualTo(400L);
        assertThat(outboxCount("FundsReversed", org)).isZero();
    }

    @Test
    void captureByTrustServiceSucceeds() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1000);
        reserve(merchant, org, ref, 600);
        // release/capture 现接受 trust 服务断言（Phase D 放宽）
        client().post().uri("/api/finance/reservations/" + ref + "/capture")
                .header("X-Grassland-Identity", signService(org, "trust"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("captured");
    }


    // ---------- 任务书 #90 C90-01：资金闸门与结算对账 ----------

    @Test
    void releaseAndCaptureByMerchantForbidden() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1000);
        reserve(merchant, org, ref, 600);
        assertThat(balanceOf(org)).isEqualTo(400L);

        // TC90-001：商家用户断言直调资金终态 → 403（必须走业务确认/取消命令）
        client().post().uri("/api/finance/reservations/" + ref + "/release")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isForbidden();
        client().post().uri("/api/finance/reservations/" + ref + "/capture")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isForbidden();
        // 状态读端点同样不允许终端用户
        client().get().uri("/api/finance/reservations/" + ref)
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isForbidden();
        // 资金未动、无事件
        assertThat(balanceOf(org)).isEqualTo(400L);
        assertThat(outboxCount("FundsReleased", org)).isZero();
        assertThat(outboxCount("FundsCaptured", org)).isZero();
    }

    @Test
    void getReservationByServiceForVerification() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1000);
        reserve(merchant, org, ref, 600);

        // D90-02 核对通道：服务断言可读预留状态/金额/组织/收款人
        client().get().uri("/api/finance/reservations/" + ref)
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("reserved")
                .jsonPath("$.data.amountCents").isEqualTo(600)
                .jsonPath("$.data.organizationId").isEqualTo(org);
        // org 不符的服务断言 → 403
        client().get().uri("/api/finance/reservations/" + ref)
                .header("X-Grassland-Identity", signService(UUID.randomUUID().toString(), "marketplace"))
                .exchange().expectStatus().isForbidden();
        // 不存在 → 404
        client().get().uri("/api/finance/reservations/eng-none")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void duplicateCapturePaysPayeeExactlyOnce() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String payee = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1000);
        reserve(merchant, org, ref, 600, payee);

        // TC90-003：capture 一次入账；重复 capture → 409，钱包与流水都只有一条
        client().post().uri("/api/finance/reservations/" + ref + "/capture")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("captured");
        client().post().uri("/api/finance/reservations/" + ref + "/capture")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isEqualTo(409);

        System.out.println("DEBUG ledger=" + db.sql(
                "SELECT string_agg(entry_type || ':' || amount_cents, ',') AS v FROM wallet_ledger"
                + " WHERE account_id = CAST(:acct AS uuid)").bind("acct", payee)
                .map(r -> r.get("v", String.class)).one().block());
        assertThat(walletPayoutEntryCount(payee)).isEqualTo(1);  // 只有一条 payout 流水
        // SplitCompleted payload 不带 organizationId，按收款人计数
        assertThat(splitEventCount(payee)).isEqualTo(1);
        assertThat(outboxCount("FundsCaptured", org)).isEqualTo(1);
    }

    // ---------- helpers ----------

    private void provision(String merchant, String org) {
        client().post().uri("/api/finance/accounts")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isCreated();
    }

    private void credit(String merchant, String org, long amount) {
        client().post().uri("/api/finance/accounts/" + org + "/credit")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("amountCents", amount))
                .exchange().expectStatus().isOk();
    }

    private void reserve(String merchant, String org, String ref, long amount) {
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", ref, "amountCents", amount))
                .exchange().expectStatus().isCreated();
    }

    private void reserve(String merchant, String org, String ref, long amount, String payee) {
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", ref, "amountCents", amount, "payeeAccountId", payee))
                .exchange().expectStatus().isCreated();
    }

    /** SplitCompleted 事件按收款人计（payload 无 organizationId，不能复用 outboxCount）。 */
    private long splitEventCount(String payeeAccountId) {
        return db.sql("SELECT COUNT(*)::int AS c FROM finance_outbox"
                        + " WHERE event_type = 'SplitCompleted' AND payload->>'payeeAccountId' = :payee")
                .bind("payee", payeeAccountId)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
    }

    /** 推荐官钱包 TASK_PAYOUT 流水条数（TC90-003：重复 capture 恰好一条）。 */
    private long walletPayoutEntryCount(String payeeAccountId) {
        return db.sql("SELECT COUNT(*)::int AS c FROM wallet_ledger"
                        + " WHERE account_id = CAST(:acct AS uuid) AND entry_type = 'task_payout'")
                .bind("acct", payeeAccountId)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
    }

    private void capture(String merchant, String org, String ref) {
        client().post().uri("/api/finance/reservations/" + ref + "/capture")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isOk();
    }

    private long balanceOf(String org) {
        return db.sql("SELECT balance_cents FROM finance_account WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", org)
                .map(r -> r.get("balance_cents", Long.class)).one().block();
    }

    private long outboxCount(String eventType, String org) {
        return db.sql("SELECT COUNT(*)::int AS c FROM finance_outbox"
                        + " WHERE event_type = :et AND payload->>'organizationId' = :org")
                .bind("et", eventType).bind("org", org)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
    }

    /** 读取按 organizationId 限定的事件 payload 顶层字段（Slice 12 Stage 3 收件人字段断言）。 */
    private String outboxPayloadField(String eventType, String org, String field) {
        return db.sql("SELECT payload->>'" + field + "' AS v FROM finance_outbox"
                        + " WHERE event_type = :et AND payload->>'organizationId' = :org")
                .bind("et", eventType).bind("org", org)
                .map(r -> r.get("v", String.class)).one().block();
    }
}
