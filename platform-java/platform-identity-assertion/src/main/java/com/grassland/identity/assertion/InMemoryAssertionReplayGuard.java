package com.grassland.identity.assertion;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内单次消费 replay guard（默认关闭）。
 *
 * <p>适用于单实例部署；扩副本时会各自独立消费 → 重放检测失效。
 * 扩副本前必须切换到共享存储（Redis/DB），见 {@code GL-P3-PLATFORM-001}。
 */
public final class InMemoryAssertionReplayGuard implements AssertionReplayGuard.SingleUse {

    private final ConcurrentMap<String, Long> consumedJti; // jti → expiresAt epoch-milli
    private final boolean enabled;

    public InMemoryAssertionReplayGuard(boolean enabled) {
        this.consumedJti = new ConcurrentHashMap<>();
        this.enabled = enabled;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean consumeOnce(String jti, Instant expiresAt) {
        if (!enabled) {
            return true;
        }
        long expiryMs = expiresAt.toEpochMilli();
        // putIfAbsent 返回 null 表示首次，返回旧值表示已消费
        Long existing = consumedJti.putIfAbsent(jti, expiryMs);
        if (existing != null) {
            return false; // 已消费
        }
        // 成功标记为已消费
        return true;
    }

    /**
     * 清理过期 jti（后台任务调用）。删除 {@code now} 之前过期的条目。
     *
     * @param now 当前时刻（可传固定值便于测试；生产传 {@link Instant#now()}）
     */
    public void cleanExpired(Instant now) {
        long nowMs = now.toEpochMilli();
        consumedJti.entrySet().removeIf(entry -> entry.getValue() < nowMs);
    }

    /** 清理过期 jti（生产便捷重载，用当前时刻）。 */
    public void cleanExpired() {
        cleanExpired(Instant.now());
    }

    /** 清空全部（仅测试用）。 */
    public void clear() {
        consumedJti.clear();
    }

    /** 当前已消费 jti 数量（监控用）。 */
    public int size() {
        return consumedJti.size();
    }
}
