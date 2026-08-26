package com.grassland.intelligence.ai.controlplane;

import static com.grassland.intelligence.config.R2dbcBindings.nullable;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 平台凭据下 admin 勾选启用的模型白名单（V51）。
 *
 * <p>「平台模型」表单的模型下拉只读这张表——不实时触网，故上游临时不可达（网络策略、
 * fake-IP DNS 劫持、上游故障）不影响改配置。实时列表只在 admin 点「获取模型」时拉一次。
 */
@Repository
public class PlatformCredentialModelRepository {

    private final DatabaseClient db;

    public PlatformCredentialModelRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<SelectedModel> findByCredential(UUID credentialId) {
        return db.sql("""
                        SELECT model_id, owned_by
                        FROM platform_credential_model
                        WHERE credential_id = :credentialId
                        ORDER BY model_id
                        """)
                .bind("credentialId", credentialId)
                .map(PlatformCredentialModelRepository::map)
                .all();
    }

    /**
     * 整份覆盖勾选集：先删该凭据下全部行，再插入新集合。
     *
     * <p>调用方必须包在事务里（{@code TransactionalOperator}）——删完插之前若失败，
     * 勾选集会变空，表单就没得选了。空集合是合法输入（= 取消全部勾选）。
     */
    public Mono<Void> replaceAll(UUID credentialId, List<SelectedModel> models, String adminId) {
        Mono<Void> cleared = db.sql("DELETE FROM platform_credential_model WHERE credential_id = :credentialId")
                .bind("credentialId", credentialId)
                .then();
        if (models.isEmpty()) {
            return cleared;
        }
        return cleared.thenMany(Flux.fromIterable(models).concatMap(model -> db.sql("""
                        INSERT INTO platform_credential_model(credential_id, model_id, owned_by, selected_by)
                        VALUES (:credentialId, :modelId, :ownedBy, :adminId)
                        """)
                .bind("credentialId", credentialId)
                .bind("modelId", model.modelId())
                .bind("ownedBy", nullable(model.ownedBy(), String.class))
                .bind("adminId", nullable(adminId, String.class))
                .then()))
                .then();
    }

    private static SelectedModel map(Row row, RowMetadata meta) {
        return new SelectedModel(row.get("model_id", String.class), row.get("owned_by", String.class));
    }

    public record SelectedModel(String modelId, String ownedBy) {
    }
}
