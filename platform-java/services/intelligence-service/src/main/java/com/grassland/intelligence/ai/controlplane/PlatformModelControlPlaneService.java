package com.grassland.intelligence.ai.controlplane;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 平台模型控制面服务（model-control-plane，HLD §12.3）。
 *
 * <p>{@link #resolve} 按能力解析当前平台模型：primary 优先；primary 不健康则 backup；都不可用则空
 * （调用方据此判定「无平台模型」）。{@link #currentVersion} 供 TaskContext 冻结版本号（HLD §6.2）。
 */
@Service
public class PlatformModelControlPlaneService {

    private final PlatformModelConfigRepository repository;

    public PlatformModelControlPlaneService(PlatformModelConfigRepository repository) {
        this.repository = repository;
    }

    /**
     * 解析某能力的平台模型（primary 健康优先，否则 backup）。无任何可用配置 → 空。
     *
     * <p>任务书 #47 S2：连凭据一起解析——baseUrl 取凭据（D2 凭据是目的地真相源），密文交执行层解密，
     * 凭据无密钥时由执行层回落 env bootstrap（D1/D8）。
     */
    public Mono<Optional<ResolvedPlatformModel>> resolve(String capability) {
        return repository.findCurrentWithCredentialByCapability(capability)
                .collectList()
                .map(rows -> {
                    PlatformModelWithCredential primary = null;
                    PlatformModelWithCredential backup = null;
                    for (PlatformModelWithCredential row : rows) {
                        if (PlatformModelConfig.ROLE_PRIMARY.equalsIgnoreCase(row.config().modelRole())) {
                            primary = row;
                        } else if (PlatformModelConfig.ROLE_BACKUP.equalsIgnoreCase(row.config().modelRole())) {
                            backup = row;
                        }
                    }
                    return Optional.ofNullable(pick(primary, backup))
                            .map(row -> new ResolvedPlatformModel(
                                    row.config().id(), row.config().provider(), row.config().model(),
                                    row.effectiveBaseUrl(), row.config().version(), row.config().modelRole(),
                                    row.config().maxConcurrency(),
                                    row.credentialId(), row.credentialEncryptedKey(), row.credentialVersion()));
                });
    }

    /** 某 (capability, model_role) 当前版本号（TaskContext 冻结用）。无配置 → 空。 */
    public Mono<Optional<Integer>> currentVersion(String capability, String modelRole) {
        return repository.findCurrent(capability, modelRole)
                .map(c -> Optional.of(c.version()))
                .defaultIfEmpty(Optional.empty());
    }

    private static PlatformModelWithCredential pick(
            PlatformModelWithCredential primary, PlatformModelWithCredential backup) {
        if (primary != null && primary.config().isHealthy()) {
            return primary;
        }
        if (backup != null && backup.config().isHealthy()) {
            return backup;
        }
        if (primary != null && primary.config().isAvailable()) {
            return primary;
        }
        return backup != null && backup.config().isAvailable() ? backup : null;
    }

    /**
     * 解析结果（运行时路由消费；version 供 TaskContext 冻结）。
     *
     * <p>{@code credentialEncryptedKey} 是<b>密文</b>，解密在执行层按需进行（{@code AiExecutionService}），
     * 绝不入日志/响应/outbox。为 null 表示该凭据无密钥（sandbox 或走 env bootstrap 兜底）。
     * {@code credentialVersion} 供 {@code ai_run.credential_version} 冻结（D7，S3 接线）。
     */
    public record ResolvedPlatformModel(
            UUID configId,
            String provider,
            String model,
            String baseUrl,
            int version,
            String modelRole,
            Integer maxConcurrency,
            UUID credentialId,
            String credentialEncryptedKey,
            Long credentialVersion) {

        /** 旧构造形状（无凭据），供既有测试与不关心凭据的调用方使用。 */
        public ResolvedPlatformModel(
                UUID configId, String provider, String model, String baseUrl,
                int version, String modelRole, Integer maxConcurrency) {
            this(configId, provider, model, baseUrl, version, modelRole, maxConcurrency, null, null, null);
        }
    }
}
