package com.grassland.identity.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.identity.IdentityItSupport;
import com.grassland.identity.auth.IdentityException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * store_media 仓储 IT（任务书 #42 Stage 2）。
 *
 * <p>守卫：写 SQL 按 {@code (organization_id, store_id)} 双限定（跨组织查空/不落行）；
 * UNIQUE(store_id, media_reference_id) → 409；帽 6/12/12/3 → 409；position 追加尾部；
 * reorder 精确集合校验 → 409；findPublic 的 store+org 双 active gate；并发绑定经
 * store 行锁串行化后帽不被突破。
 */
class StoreMediaRepositoryIT extends IdentityItSupport {

    @Autowired
    private StoreMediaRepository repository;

    @Autowired
    private TransactionalOperator transactions;

    @Test
    void bindAssignsSequentialPositionsAndRoundTrips() {
        var owner = seedAccount("store-media-repo-roundtrip@example.com");
        String orgId = createOrg(owner.cookie(), "媒体仓储主体");
        String storeId = createStore(orgId, owner.cookie(), "媒体仓储门店");

        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        List<StoreMediaBinding> bound = repository.bind(orgId, storeId, StoreMediaKind.STOREFRONT,
                List.of(new StoreMediaRepository.NewBinding(first, "image/png", 1024L),
                        new StoreMediaRepository.NewBinding(second, "image/jpeg", 2048L)),
                owner.accountId()).collectList().block();
        assertThat(bound).hasSize(2);
        assertThat(bound.get(0).position()).isEqualTo(1);
        assertThat(bound.get(1).position()).isEqualTo(2);
        assertThat(bound.get(0).mimeType()).isEqualTo("image/png");
        assertThat(bound.get(0).uploadedByAccountId()).isEqualTo(owner.accountId());
        assertThat(bound.get(0).organizationId()).isEqualTo(orgId);

        List<StoreMediaBinding> reloaded = repository.findByOrganizationAndStore(orgId, storeId)
                .collectList().block();
        assertThat(reloaded).hasSize(2);
        assertThat(reloaded.get(0).mediaReferenceId()).isEqualTo(first);
        assertThat(reloaded.get(1).mediaReferenceId()).isEqualTo(second);
    }

    @Test
    void crossOrgScopedWritesAndReadsStayEmpty() {
        var ownerA = seedAccount("store-media-repo-org-a@example.com");
        String orgA = createOrg(ownerA.cookie(), "媒体仓储主体A");
        String storeId = createStore(orgA, ownerA.cookie(), "A店");
        var ownerB = seedAccount("store-media-repo-org-b@example.com");
        String orgB = createOrg(ownerB.cookie(), "媒体仓储主体B");

        // 用 orgB 作用域绑 A 店 → 门店不属于该组织 → 404（第二道闸）。
        assertThatThrownBy(() -> repository.bind(orgB, storeId, StoreMediaKind.STOREFRONT,
                List.of(new StoreMediaRepository.NewBinding(UUID.randomUUID().toString(), "image/png", 1L)),
                ownerB.accountId()).collectList().block())
                .isInstanceOf(IdentityException.class)
                .satisfies(error -> assertThat(((IdentityException) error).status()).isEqualTo(404));

        // A 店先正常绑一条，再用 orgB 作用域读/解绑/重排 → 全部查空，A 店数据原样。
        String mediaId = UUID.randomUUID().toString();
        repository.bind(orgA, storeId, StoreMediaKind.STOREFRONT,
                List.of(new StoreMediaRepository.NewBinding(mediaId, "image/png", 1L)),
                ownerA.accountId()).collectList().block();
        assertThat(repository.findByOrganizationAndStore(orgB, storeId).collectList().block()).isEmpty();
        assertThat(repository.unbind(orgB, storeId, mediaId).block()).isFalse();
        assertThatThrownBy(() -> repository.reorder(orgB, storeId, StoreMediaKind.STOREFRONT,
                List.of(mediaId)).block())
                .isInstanceOf(IdentityException.class);
        assertThat(repository.findByOrganizationAndStore(orgA, storeId).collectList().block()).hasSize(1);
    }

    @Test
    void duplicateBindIsConflict() {
        var owner = seedAccount("store-media-repo-unique@example.com");
        String orgId = createOrg(owner.cookie(), "UNIQUE 主体");
        String storeId = createStore(orgId, owner.cookie(), "UNIQUE 门店");
        String mediaId = UUID.randomUUID().toString();

        repository.bind(orgId, storeId, StoreMediaKind.STOREFRONT,
                List.of(new StoreMediaRepository.NewBinding(mediaId, "image/png", 1L)),
                owner.accountId()).collectList().block();

        // UNIQUE(store_id, media_reference_id) 冲突 → 409「媒体已绑定该门店」（跨分类也冲突）。
        assertThatThrownBy(() -> repository.bind(orgId, storeId, StoreMediaKind.ENVIRONMENT,
                List.of(new StoreMediaRepository.NewBinding(mediaId, "image/png", 1L)),
                owner.accountId()).collectList().block())
                .isInstanceOf(IdentityException.class)
                .satisfies(error -> {
                    IdentityException e = (IdentityException) error;
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("媒体已绑定该门店");
                });
    }

    @Test
    void storefrontCapIsSix() {
        var owner = seedAccount("store-media-repo-cap@example.com");
        String orgId = createOrg(owner.cookie(), "帽主体");
        String storeId = createStore(orgId, owner.cookie(), "帽门店");

        List<StoreMediaRepository.NewBinding> six = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(new StoreMediaRepository.NewBinding(
                    UUID.randomUUID().toString(), "image/png", 1L));
        }
        repository.bind(orgId, storeId, StoreMediaKind.STOREFRONT, six, owner.accountId())
                .collectList().block();

        // 第 7 张门头 → 409「该分类数量已达上限」（INSERT 前 count 校验，不落行）。
        assertThatThrownBy(() -> repository.bind(orgId, storeId, StoreMediaKind.STOREFRONT,
                List.of(new StoreMediaRepository.NewBinding(UUID.randomUUID().toString(), "image/png", 1L)),
                owner.accountId()).collectList().block())
                .isInstanceOf(IdentityException.class)
                .satisfies(error -> {
                    IdentityException e = (IdentityException) error;
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("该分类数量已达上限");
                });
        assertThat(repository.findByOrganizationAndStore(orgId, storeId).collectList().block()).hasSize(6);

        // 其它分类不受门头帽影响：环境照片仍可绑。
        repository.bind(orgId, storeId, StoreMediaKind.ENVIRONMENT,
                List.of(new StoreMediaRepository.NewBinding(UUID.randomUUID().toString(), "image/png", 1L)),
                owner.accountId()).collectList().block();
        assertThat(repository.findByOrganizationAndStore(orgId, storeId).collectList().block()).hasSize(7);
    }

    @Test
    void concurrentBindsAreSerializedByStoreRowLockAndCapHolds() {
        var owner = seedAccount("store-media-repo-concurrent@example.com");
        String orgId = createOrg(owner.cookie(), "并发帽主体");
        String storeId = createStore(orgId, owner.cookie(), "并发帽门店");

        // 预置 5 张门头（5/6），再并发两个各绑 1 张的事务：无锁时两者都会读到 total=5 双双放行落 7 条。
        transactions.transactional(repository.bind(orgId, storeId, StoreMediaKind.STOREFRONT,
                List.of(new StoreMediaRepository.NewBinding(UUID.randomUUID().toString(), "image/png", 1L),
                        new StoreMediaRepository.NewBinding(UUID.randomUUID().toString(), "image/png", 1L),
                        new StoreMediaRepository.NewBinding(UUID.randomUUID().toString(), "image/png", 1L),
                        new StoreMediaRepository.NewBinding(UUID.randomUUID().toString(), "image/png", 1L),
                        new StoreMediaRepository.NewBinding(UUID.randomUUID().toString(), "image/png", 1L)),
                owner.accountId()).then()).block();

        Mono<Boolean> attempt = Mono.defer(() -> transactions.transactional(
                        repository.bind(orgId, storeId, StoreMediaKind.STOREFRONT,
                                List.of(new StoreMediaRepository.NewBinding(
                                        UUID.randomUUID().toString(), "image/png", 1L)),
                                owner.accountId()).then(Mono.just(true))))
                .onErrorResume(IdentityException.class, error -> {
                    // 后到事务在 store 行锁释放后重读 stats → 6+1>6 → 409。
                    assertThat(error.status()).isEqualTo(409);
                    assertThat(error.getMessage()).isEqualTo("该分类数量已达上限");
                    return Mono.just(false);
                });
        List<Boolean> outcomes = Mono.zip(attempt, attempt)
                .map(tuple -> List.of(tuple.getT1(), tuple.getT2())).block();

        // 恰有一个成功：FOR UPDATE 串行化同店写，帽（6）不被突破，position 不撞号。
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        List<StoreMediaBinding> reloaded = repository.findByOrganizationAndStore(orgId, storeId)
                .collectList().block();
        assertThat(reloaded).hasSize(6);
        assertThat(reloaded.stream().filter(b -> "storefront".equals(b.kind()))
                .map(StoreMediaBinding::position).distinct().count()).isEqualTo(6);
    }

    @Test
    void reorderValidatesExactSetAndRewritesPositions() {
        var owner = seedAccount("store-media-repo-reorder@example.com");
        String orgId = createOrg(owner.cookie(), "重排主体");
        String storeId = createStore(orgId, owner.cookie(), "重排门店");
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        String c = UUID.randomUUID().toString();
        repository.bind(orgId, storeId, StoreMediaKind.MENU,
                List.of(new StoreMediaRepository.NewBinding(a, "image/png", 1L),
                        new StoreMediaRepository.NewBinding(b, "image/png", 1L),
                        new StoreMediaRepository.NewBinding(c, "image/png", 1L)),
                owner.accountId()).collectList().block();

        // 缺项集合 → 409。
        assertThatThrownBy(() -> repository.reorder(orgId, storeId, StoreMediaKind.MENU, List.of(a, b)).block())
                .isInstanceOf(IdentityException.class)
                .satisfies(error -> {
                    IdentityException e = (IdentityException) error;
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("排序列表与该分类当前媒体不一致");
                });
        // 多项集合（含它店/未绑定 id）→ 409。
        assertThatThrownBy(() -> repository.reorder(orgId, storeId, StoreMediaKind.MENU,
                List.of(a, b, c, UUID.randomUUID().toString())).block())
                .isInstanceOf(IdentityException.class);
        // 重复项 → 409。
        assertThatThrownBy(() -> repository.reorder(orgId, storeId, StoreMediaKind.MENU, List.of(a, a, b)).block())
                .isInstanceOf(IdentityException.class);

        // 精确集合（乱序）→ 成功，position 按请求顺序重写。
        repository.reorder(orgId, storeId, StoreMediaKind.MENU, List.of(c, a, b)).block();
        List<StoreMediaBinding> reloaded = repository.findByOrganizationAndStore(orgId, storeId)
                .collectList().block();
        assertThat(reloaded.stream().map(StoreMediaBinding::mediaReferenceId).toList())
                .containsExactly(c, a, b);
        assertThat(reloaded.stream().map(StoreMediaBinding::position).toList())
                .containsExactly(1, 2, 3);
    }

    @Test
    void unbindRemovesOnlyBindingRowAndLeavesPositionHoles() {
        var owner = seedAccount("store-media-repo-unbind@example.com");
        String orgId = createOrg(owner.cookie(), "解绑主体");
        String storeId = createStore(orgId, owner.cookie(), "解绑门店");
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        repository.bind(orgId, storeId, StoreMediaKind.STOREFRONT,
                List.of(new StoreMediaRepository.NewBinding(a, "image/png", 1L),
                        new StoreMediaRepository.NewBinding(b, "image/png", 1L)),
                owner.accountId()).collectList().block();

        assertThat(repository.unbind(orgId, storeId, a).block()).isTrue();
        assertThat(repository.unbind(orgId, storeId, a).block()).isFalse();

        // 解绑留空洞不重排（D10）：b 仍 position=2。
        List<StoreMediaBinding> remaining = repository.findByOrganizationAndStore(orgId, storeId)
                .collectList().block();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).mediaReferenceId()).isEqualTo(b);
        assertThat(remaining.get(0).position()).isEqualTo(2);

        // 新绑定追加尾部：position=3（当前 max+1，非空洞回补）。
        String c = UUID.randomUUID().toString();
        repository.bind(orgId, storeId, StoreMediaKind.STOREFRONT,
                List.of(new StoreMediaRepository.NewBinding(c, "image/png", 1L)),
                owner.accountId()).collectList().block();
        List<StoreMediaBinding> after = repository.findByOrganizationAndStore(orgId, storeId)
                .collectList().block();
        assertThat(after).hasSize(2);
        assertThat(after.get(1).mediaReferenceId()).isEqualTo(c);
        assertThat(after.get(1).position()).isEqualTo(3);
    }

    @Test
    void findPublicIsGatedByStoreAndOrgStatus() {
        var owner = seedAccount("store-media-repo-public@example.com");
        String orgId = createOrg(owner.cookie(), "公开读主体");
        String storeId = createStore(orgId, owner.cookie(), "公开读门店");
        repository.bind(orgId, storeId, StoreMediaKind.VIDEO,
                List.of(new StoreMediaRepository.NewBinding(UUID.randomUUID().toString(), "video/mp4", 1L)),
                owner.accountId()).collectList().block();

        assertThat(repository.isPubliclyReadable(storeId).block()).isTrue();
        assertThat(repository.findPublic(storeId).collectList().block()).hasSize(1);

        // 门店停用 → gate 失败。
        db.sql("UPDATE store SET status = 'inactive' WHERE id = CAST(:id AS uuid)")
                .bind("id", storeId).then().block();
        assertThat(repository.isPubliclyReadable(storeId).block()).isFalse();
        assertThat(repository.findPublic(storeId).collectList().block()).isEmpty();
        db.sql("UPDATE store SET status = 'active' WHERE id = CAST(:id AS uuid)")
                .bind("id", storeId).then().block();

        // 组织 suspended → gate 失败。
        db.sql("UPDATE organization SET status = 'suspended' WHERE id = CAST(:id AS uuid)")
                .bind("id", orgId).then().block();
        assertThat(repository.isPubliclyReadable(storeId).block()).isFalse();
        assertThat(repository.findPublic(storeId).collectList().block()).isEmpty();
        db.sql("UPDATE organization SET status = 'active' WHERE id = CAST(:id AS uuid)")
                .bind("id", orgId).then().block();

        // 未知 UUID → 查空。
        assertThat(repository.isPubliclyReadable(UUID.randomUUID().toString()).block()).isFalse();
    }
}
