package com.grassland.finance.ledger;

import com.grassland.finance.account.AccountRepository;
import com.grassland.finance.wallet.WalletRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 投影重建校验（HLD §6.4「缓存余额只是投影，必须可由 Posting 重建」）。
 *
 * <p>双写投影（Approach B）下，余额行是缓存投影、账本是真相源。本服务重算账本派生余额
 * （{@code SUM(credit) - SUM(debit)}）对比物化余额行，证明二者一致——任何偏差都意味着双写漏了一笔。
 *
 * <p>用途：IT 校验全链路（credit→reserve→capture→reverse→withdraw）后投影可重建；将来可挂运营/对账端点。
 */
@Component
public class LedgerProjectionService {

    private final LedgerRepository ledger;
    private final AccountRepository accounts;
    private final WalletRepository wallets;

    public LedgerProjectionService(LedgerRepository ledger, AccountRepository accounts, WalletRepository wallets) {
        this.ledger = ledger;
        this.accounts = accounts;
        this.wallets = wallets;
    }

    /** ESCROW 物化余额（无账户视作 0）是否等于账本派生余额。 */
    public Mono<Boolean> reconcileEscrow(String orgId) {
        Mono<Long> materialized = accounts.findByOrganization(orgId)
                .map(acct -> acct.balanceCents())
                .defaultIfEmpty(0L);
        return materialized.zipWith(ledger.sumBalance(LedgerAccount.Type.ESCROW, orgId))
                .map(tuple -> tuple.getT1().equals(tuple.getT2()))
                .defaultIfEmpty(true);
    }

    /** WALLET 物化余额（无钱包视作 0）是否等于账本派生余额。 */
    public Mono<Boolean> reconcileWallet(String accountId) {
        Mono<Long> materialized = wallets.findByAccount(accountId)
                .map(wallet -> wallet.balanceCents())
                .defaultIfEmpty(0L);
        return materialized.zipWith(ledger.sumBalance(LedgerAccount.Type.WALLET, accountId))
                .map(tuple -> tuple.getT1().equals(tuple.getT2()))
                .defaultIfEmpty(true);
    }
}
