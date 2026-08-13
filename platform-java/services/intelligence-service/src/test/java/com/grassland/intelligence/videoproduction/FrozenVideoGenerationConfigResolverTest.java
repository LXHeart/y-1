package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class FrozenVideoGenerationConfigResolverTest {
    @Test
    void normalizesProviderIndependentlyOfDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            VideoGenerationProperties properties = new VideoGenerationProperties();
            properties.setMode("MINIMAX");
            properties.setBaseUrl("https://api.example.com");
            properties.setApiKey("secret");
            properties.setModel("video-01");

            FrozenVideoGenerationConfigResolver resolver =
                    new FrozenVideoGenerationConfigResolver(properties);

            assertThat(resolver.current().provider()).isEqualTo("minimax");
        } finally {
            Locale.setDefault(original);
        }
    }
}
