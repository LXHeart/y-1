package com.grassland.intelligence.articleimage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.grassland.storage.ObjectStorageAdapter;
import com.grassland.storage.StoredObject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** S3GeneratedImageStore 的 TTL/key/cleanup 逻辑（Mockito，不连真实存储）；端到端见 S3GeneratedImageStoreIT。 */
@ExtendWith(MockitoExtension.class)
class S3GeneratedImageStoreTest {

    private static final Instant NOW = Instant.parse("2026-07-29T10:00:00Z");
    private static final byte[] PNG = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47};

    @Mock
    private ObjectStorageAdapter storage;

    private S3GeneratedImageStore newStore(String prefix) {
        return new S3GeneratedImageStore(storage, prefix, 1800, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void store_putsObjectUnderKeyPrefixAndReturnsUuid() {
        S3GeneratedImageStore store = newStore("article-generated");

        String id = store.store(Base64.getEncoder().encodeToString(PNG)).block();

        assertThat(id).matches("[0-9a-f-]{36}");
        verify(storage).putObject("article-generated/" + id + ".png", PNG, "image/png");
    }

    @Test
    void find_returnsBytesWhenFresh() {
        S3GeneratedImageStore store = newStore("p");
        String id = UUID.randomUUID().toString();
        when(storage.headObject("p/" + id + ".png")).thenReturn(Optional.of(
                new StoredObject("p/" + id + ".png", PNG.length, "image/png", "etag", NOW)));
        when(storage.getObject("p/" + id + ".png")).thenReturn(PNG);

        GeneratedImageStore.StoredImage result = store.find(id).block();

        assertThat(result).isNotNull();
        assertThat(result.bytes()).isEqualTo(PNG);
        verify(storage, never()).deleteObject(anyString());
    }

    @Test
    void find_deletesAndReturnsEmptyWhenExpired() {
        S3GeneratedImageStore store = newStore("p");
        String id = UUID.randomUUID().toString();
        Instant stale = NOW.minus(Duration.ofMinutes(31));
        when(storage.headObject("p/" + id + ".png")).thenReturn(Optional.of(
                new StoredObject("p/" + id + ".png", PNG.length, null, "etag", stale)));

        assertThat(store.find(id).block()).isNull();
        verify(storage).deleteObject("p/" + id + ".png");
        verify(storage, never()).getObject(anyString());
    }

    @Test
    void find_absentReturnsEmptyWithoutExtraCalls() {
        S3GeneratedImageStore store = newStore("p");
        String id = UUID.randomUUID().toString();
        when(storage.headObject("p/" + id + ".png")).thenReturn(Optional.empty());

        assertThat(store.find(id).block()).isNull();
        verify(storage, never()).getObject(anyString());
        verify(storage, never()).deleteObject(anyString());
    }

    @Test
    void find_rejectsInvalidIdWithoutHead() {
        S3GeneratedImageStore store = newStore("p");

        assertThat(store.find("../secret").block()).isNull();
        assertThat(store.find("NOT-A-UUID").block()).isNull();

        verifyNoInteractions(storage);
    }

    @Test
    void cleanupBlocking_deletesOnlyStaleObjects() {
        S3GeneratedImageStore store = newStore("p");
        StoredObject fresh = new StoredObject("p/fresh.png", 4, null, "e", NOW);
        StoredObject stale = new StoredObject("p/stale.png", 4, null, "e", NOW.minus(Duration.ofMinutes(40)));
        when(storage.listObjects("p")).thenReturn(List.of(fresh, stale));

        store.cleanupBlocking();

        verify(storage).deleteObject("p/stale.png");
        verify(storage, never()).deleteObject("p/fresh.png");
    }
}
