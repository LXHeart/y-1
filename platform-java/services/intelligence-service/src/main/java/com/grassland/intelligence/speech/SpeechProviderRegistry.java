package com.grassland.intelligence.speech;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class SpeechProviderRegistry {

    private final Map<String, SpeechRecognitionProvider> providers;

    public SpeechProviderRegistry(List<SpeechRecognitionProvider> providers) {
        Map<String, SpeechRecognitionProvider> indexed = new LinkedHashMap<>();
        for (SpeechRecognitionProvider provider : providers) {
            String name = normalize(provider.provider());
            if (name == null || indexed.putIfAbsent(name, provider) != null) {
                throw new IllegalStateException("语音模型供应商注册重复或为空");
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    public SpeechRecognitionProvider require(String name) {
        SpeechRecognitionProvider provider = providers.get(normalize(name));
        if (provider == null) {
            throw new IntelligenceException(
                    503, "unsupported_provider", "暂不支持该语音模型供应商");
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
