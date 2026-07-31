package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link LinkReachabilityChecker} 的 SSRF 拒绝路径（Verification v1）。
 *
 * <p>SSRF 守卫（{@link LinkUrlGuard}）刻意拒绝私有/环回/非 http 地址 → inconclusive，故用 localhost 起 WireMock
 * 反而会被守卫拦掉（无法测真 HTTP 状态映射）。这里覆盖守卫拒绝路径（安全关键）；HTTP 状态→tri-state 的分类
 * 逻辑（2xx→passed/404→failed/5xx→inconclusive）由 {@code SettlementActivityImplTest} 的 capture 闸门与
 * 端到端 IT 间接覆盖，真链路在浏览器 e2e 验。
 */
class LinkReachabilityCheckerTest {

    private final LinkReachabilityChecker checker = new LinkReachabilityChecker(3000);

    @Test
    void rejectsLoopbackAsInconclusive() {
        LinkReachabilityChecker.CheckResult r = checker.check("http://127.0.0.1/x").block();
        assertThat(r.status()).isEqualTo("inconclusive");
        assertThat(r.detail()).contains("内网");
    }

    @Test
    void rejectsNonHttpAsInconclusive() {
        assertThat(checker.check("ftp://example.com/x").block().status()).isEqualTo("inconclusive");
    }

    @Test
    void rejectsBlankAsInconclusive() {
        assertThat(checker.check("").block().status()).isEqualTo("inconclusive");
    }
}
