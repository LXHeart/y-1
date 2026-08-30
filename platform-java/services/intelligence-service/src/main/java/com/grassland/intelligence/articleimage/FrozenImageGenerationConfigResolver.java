package com.grassland.intelligence.articleimage;

import com.grassland.intelligence.ai.controlplane.PlatformModelControlPlaneService;
import com.grassland.intelligence.ai.controlplane.PlatformModelControlPlaneService.ResolvedPlatformModel;
import com.grassland.intelligence.creationcontext.CreationContextSnapshot;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 任务创作图像配置冻结（任务书 #56；任务书 #58 决策 G 起模型层改以控制面 image_generation 行为源）。
 *
 * <p><b>冻结语义不变</b>：provider/model/价目版本/单价在创作开始时冻结、密钥不入快照、执行时漂移
 * 即 409。变化只在来源——静态 env 六字段（含 baseUrl/apiKey 指纹）换成「控制面行 provider/model +
 * 静态价目三字段」；runtimeFingerprint 随 env 端点一起删除（控制面行自身的版本演进由漂移比对覆盖：
 * revise 改 provider/model 会被 409 拒绝，只改并发/健康不影响执行语义）。
 *
 * <p>存量快照（#56 形态六字段）兼容读取：provider/model/价目字段同名可比，指纹字段忽略。
 */
@Service
public class FrozenImageGenerationConfigResolver {

    private final ImageGenerationConfig pricingConfig;
    private final PlatformModelControlPlaneService models;

    public FrozenImageGenerationConfigResolver(
            ImageGenerationConfig pricingConfig, PlatformModelControlPlaneService models) {
        this.pricingConfig = pricingConfig;
        this.models = models;
    }

    /**
     * 任务创建时冻结平台图像段（reactive：模型层来自控制面行）。无行 → {@code status=unavailable}
     * + 价目三字段（BYOK 命中的任务不走这里；执行时无行即 503）。
     */
    public Mono<Map<String, Object>> platformSnapshot() {
        return models.resolve("image_generation").map(opt -> {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("pricingVersion", pricingConfig.pricingVersion());
            config.put("unitPriceCents", pricingConfig.unitPriceCents());
            if (opt.isEmpty()) {
                config.put("status", "unavailable");
                return config;
            }
            ResolvedPlatformModel row = opt.get();
            config.put("provider", row.provider());
            config.put("model", row.model());
            config.put("platformModelVersion", row.version());
            return config;
        });
    }

    /** 治理台公共素材批量生成用：当前价目（无任务快照，按当前价收）。 */
    public Pricing currentPricing() {
        return new Pricing(pricingConfig.pricingVersion(), pricingConfig.unitPriceCents());
    }

    /**
     * 执行时解冻 + 漂移校验：价目按冻结值结算；provider/model 与当前控制面行比对，
     * 不一致 → 409（与 #56「冻结的配置已变化」同语义）。快照冻结时无行（status=unavailable）
     * 则跳过模型比对（当时未承诺任何模型）。
     */
    public Mono<ResolvedExecution> resolve(CreationContextSnapshot snapshot) {
        Object raw = snapshot.aiConfigSnapshot().get("imageGeneration");
        if (!(raw instanceof Map<?, ?> map)) {
            return Mono.error(new IntelligenceException(409, "创作上下文缺少冻结的图片生成配置"));
        }
        Pricing pricing = new Pricing(text(map, "pricingVersion"), integer(map, "unitPriceCents"));
        boolean frozenUnavailable = "unavailable".equals(String.valueOf(map.get("status")));
        String frozenProvider = map.get("provider") == null ? null : String.valueOf(map.get("provider"));
        String frozenModel = map.get("model") == null ? null : String.valueOf(map.get("model"));
        return models.resolve("image_generation").flatMap(opt -> opt
                .<Mono<ResolvedExecution>>map(row -> {
                    if (!frozenUnavailable && frozenProvider != null && frozenModel != null
                            && (!frozenProvider.equals(row.provider()) || !frozenModel.equals(row.model()))) {
                        return Mono.<ResolvedExecution>error(new IntelligenceException(
                                409, "创作开始时冻结的图片生成配置已变化或不可用"));
                    }
                    return Mono.just(new ResolvedExecution(row, pricing));
                })
                .orElseGet(() -> Mono.error(new IntelligenceException(
                        503, "no_platform_model", "平台未配置图片生成模型，请到治理台配置"))));
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IntelligenceException(409, "创作上下文中的图片生成配置不完整");
        }
        return String.valueOf(value);
    }

    private static int integer(Map<?, ?> map, String key) {
        try {
            return Integer.parseInt(text(map, key));
        } catch (NumberFormatException error) {
            throw new IntelligenceException(409, "创作上下文中的图片生成配置不合法");
        }
    }

    /** 冻结的价目（结算按任务创建时的单价，运营调价不影响进行中的任务）。 */
    public record Pricing(String pricingVersion, int unitPriceCents) {
    }

    /** 解冻结果：当前控制面行（带凭据，可直接组装 ProviderResolution）+ 冻结价目。 */
    public record ResolvedExecution(ResolvedPlatformModel platform, Pricing pricing) {
    }
}
