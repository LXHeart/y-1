package com.grassland.intelligence.articleimage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 本地卷兜底实现（未启对象存储时）的 TTL 与读写契约；S3 实现见 {@code S3GeneratedImageStoreTest}。 */
class GeneratedImageStoreTest {

    private static final byte[] PNG = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 1, 2, 3};

    @TempDir
    Path directory;

    @Test
    @DisplayName("stores b64 PNG under UUID and reads exact bytes before expiry")
    void storesAndReadsImage() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T10:00:00Z"));
        GeneratedImageStore store = new LocalGeneratedImageStore(directory, clock, Duration.ofMinutes(30));

        String id = store.store(Base64.getEncoder().encodeToString(PNG)).block();
        GeneratedImageStore.StoredImage result = store.find(id).block();

        assertThat(id).matches("[0-9a-f-]{36}");
        assertThat(result).isNotNull();
        assertThat(result.bytes()).isEqualTo(PNG);
    }

    @Test
    @DisplayName("expired images are hidden and removed")
    void expiresImageAfterThirtyMinutes() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T10:00:00Z"));
        GeneratedImageStore store = new LocalGeneratedImageStore(directory, clock, Duration.ofMinutes(30));
        String id = store.store(Base64.getEncoder().encodeToString(PNG)).block();

        clock.advance(Duration.ofMinutes(31));

        assertThat(store.find(id).block()).isNull();
        assertThat(directory.resolve(id + ".png")).doesNotExist();
    }

    @Test
    @DisplayName("invalid identifiers never resolve")
    void rejectsInvalidIdentifiers() {
        GeneratedImageStore store = new LocalGeneratedImageStore(directory, Clock.systemUTC(), Duration.ofMinutes(30));

        assertThat(store.find("../secret").block()).isNull();
        assertThat(store.find("00000000-0000-0000-0000-000000000000.png").block()).isNull();
        assertThat(store.find("NOT-A-UUID").block()).isNull();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
