package com.grassland.intelligence.mediaplatform.segments;

import com.grassland.storage.ObjectStorageAdapter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Account-scoped, atomic object entries. Never exposes object keys to callers. */
@Component
public class SharedSegmentCache {
    static final String PREFIX = "shared-visual-segments/v1/";
    static final Duration TTL = Duration.ofDays(7);
    private static final long MAX_ACCOUNT_BYTES = 1024L * 1024 * 1024;
    private final ObjectProvider<ObjectStorageAdapter> stores;
    public SharedSegmentCache(ObjectProvider<ObjectStorageAdapter> stores) { this.stores = stores; }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String ownerPrefix(String account) {
        if (account == null || account.isBlank()) throw new IllegalArgumentException("Missing owner");
        return PREFIX + hash(account.getBytes(StandardCharsets.UTF_8)) + "/";
    }
    public String fingerprint(byte[] source, SegmentSpec spec, String toolchain) {
        return hash((spec.canonical() + "|" + toolchain + "|" + hash(source)).getBytes(StandardCharsets.UTF_8));
    }
    public byte[] read(String account, String fingerprint) {
        var store = stores.getIfAvailable();
        if (store == null) return null;
        String key = ownerPrefix(account) + fingerprint;
        try {
            var head = store.headObject(key);
            if (head.isEmpty() || head.get().contentLength() > SharedSegmentRenderer.MAX_BYTES + 40) return null;
            byte[] entry = store.getObject(key);
            if (entry.length <= 40) return null;
            long created = ByteBuffer.wrap(entry, 0, 8).getLong();
            if (created > System.currentTimeMillis() || created < System.currentTimeMillis() - TTL.toMillis()) {
                store.deleteObject(key); return null;
            }
            byte[] payload = Arrays.copyOfRange(entry, 40, entry.length);
            byte[] expected = HexFormat.of().parseHex(hash(payload));
            return MessageDigest.isEqual(expected, Arrays.copyOfRange(entry, 8, 40)) ? payload : null;
        } catch (RuntimeException error) { return null; }
    }
    public void write(String account, String fingerprint, byte[] output) {
        var store = stores.getIfAvailable();
        if (store == null || Thread.currentThread().isInterrupted()) return;
        try {
            // One PUT commits timestamp, integrity digest and media together.
            byte[] entry = ByteBuffer.allocate(40 + output.length).putLong(System.currentTimeMillis())
                    .put(HexFormat.of().parseHex(hash(output))).put(output).array();
            String prefix = ownerPrefix(account);
            store.putObject(prefix + fingerprint, entry, "application/octet-stream");
            var objects = store.listObjects(prefix).stream().sorted(java.util.Comparator.comparing(o -> o.lastModified())).toList();
            long total = objects.stream().mapToLong(o -> o.contentLength()).sum();
            for (var object : objects) {
                if (total <= MAX_ACCOUNT_BYTES) break;
                store.deleteObject(object.key()); total -= object.contentLength();
            }
        } catch (RuntimeException error) { /* Advisory cache never fails an export. */ }
    }
    @Scheduled(fixedDelayString = "${media.platform.segment-cache-sweep-ms:3600000}")
    public void sweepExpired() {
        var store = stores.getIfAvailable();
        if (store == null) return;
        try {
            Instant before = Instant.now().minus(TTL);
            for (var object : store.listObjects(PREFIX)) {
                if (object.lastModified() != null && object.lastModified().isBefore(before)) store.deleteObject(object.key());
            }
        } catch (RuntimeException error) { /* Retry next sweep, only this cache prefix. */ }
    }
}
