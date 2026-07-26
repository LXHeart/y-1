package com.grassland.finance.wallet;

import java.time.Instant;

/**
 * 推荐官钱包（账号级）。草场资金闭环：capture 分账入账、提现出账。
 *
 * <p>为什么不复用 {@code finance_account}：那张表是 {@code UNIQUE(organization_id)} 的商家账户，
 * 而推荐官不属于任何 org。硬塞会把「组织账户」这个不变式弄脏。
 */
public record Wallet(String accountId, long balanceCents, Instant updatedAt) {}
