package com.grassland.intelligence.ai.controlplane;

import java.util.Optional;
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

    /** 解析某能力的平台模型（primary 健康优先，否则 backup）。无任何可用配置 → 空。 */
    public Mono<Optional<ResolvedPlatformModel>> resolve(String capability) {
        return repository.findCurrentByCapability(capability)
                .collectList()
                .map(rows -> {
                    PlatformModelConfig primary = null;
                    PlatformModelConfig backup = null;
                    for (PlatformModelConfig c : rows) {
                        if (PlatformModelConfig.ROLE_PRIMARY.equalsIgnoreCase(c.modelRole())) {
                            primary = c;
                        } else if (PlatformModelConfig.ROLE_BACKUP.equalsIgnoreCase(c.modelRole())) {
                            backup = c;
                        }
                    }
                    PlatformModelConfig chosen = pick(primary, backup);
                    return Optional.ofNullable(chosen)
                            .map(c -> new ResolvedPlatformModel(
                                    c.provider(), c.model(), c.baseUrl(), c.version(), c.modelRole()));
                });
    }

    /** 某 (capability, model_role) 当前版本号（TaskContext 冻结用）。无配置 → 空。 */
    public Mono<Optional<Integer>> currentVersion(String capability, String modelRole) {
        return repository.findCurrent(capability, modelRole)
                .map(c -> Optional.of(c.version()))
                .defaultIfEmpty(Optional.empty());
    }

    private static PlatformModelConfig pick(PlatformModelConfig primary, PlatformModelConfig backup) {
        if (primary != null && primary.isHealthy()) {
            return primary;
        }
        // primary 缺失或不健康 → backup（只要存在就用，含 degraded；unhealthy 仍兜底优于无）
        if (backup != null) {
            return backup;
        }
        // primary 存在但不健康、无 backup：仍返回 primary（让调用方/健康检查感知，优于直接判定无模型）
        return primary;
    }

    /** 解析结果（运行时路由消费；version 供 TaskContext 冻结）。 */
    public record ResolvedPlatformModel(
            String provider,
            String model,
            String baseUrl,
            int version,
            String modelRole) {
    }
}
