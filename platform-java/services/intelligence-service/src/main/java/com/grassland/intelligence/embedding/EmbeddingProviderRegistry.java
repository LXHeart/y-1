package com.grassland.intelligence.embedding;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 检索 Provider 注册表：路由只按名字解析；未安装的类型 fail-closed 503 unsupported_provider。 */
@Component
public final class EmbeddingProviderRegistry {

    private final Map<String, EmbeddingProvider> providers;

    public EmbeddingProviderRegistry(List<EmbeddingProvider> providers) {
        Map<String, EmbeddingProvider> indexed = new LinkedHashMap<>();
        for (EmbeddingProvider provider : providers) {
            String name = normalize(provider.provider());
            if (name == null || indexed.putIfAbsent(name, provider) != null) {
                throw new IllegalStateException("Embedding 模型供应商注册重复或为空");
            }
            for (String aliasValue : provider.aliases()) {
                String alias = normalize(aliasValue);
                if (alias == null || indexed.putIfAbsent(alias, provider) != null) {
                    throw new IllegalStateException("Embedding 模型供应商别名注册重复或为空");
                }
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    public EmbeddingProvider require(String name) {
        EmbeddingProvider provider = providers.get(normalize(name));
        if (provider == null) {
            throw new IntelligenceException(
                    503, "unsupported_provider", "暂不支持该Embedding模型供应商");
        }
        return provider;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
