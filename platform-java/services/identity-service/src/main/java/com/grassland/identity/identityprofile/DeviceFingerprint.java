package com.grassland.identity.identityprofile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * 从请求头提取的设备指纹。草场身份域 Slice 2I（HLD D-08 多设备视图）。
 *
 * <p>{@code deviceId} = sha256(userAgent) 前 16 hex（无 UA 时用常量），作为多设备去重/标识；
 * {@code ipAddress} 优先取受控 Nginx 追加的 {@code X-Forwarded-For} 最右一段，否则取 socket 远端地址。
 * {@code deviceLabel} 来自客户端可选自报头 {@code X-Device-Label}。
 */
public record DeviceFingerprint(String deviceId, String deviceLabel, String ipAddress, String userAgent) {

    public static DeviceFingerprint from(ServerHttpRequest request) {
        String ua = header(request, "user-agent");
        String label = header(request, "x-device-label");
        return new DeviceFingerprint(hashDeviceId(ua), label, resolveIp(request), ua);
    }

    private static String header(ServerHttpRequest request, String name) {
        String value = request.getHeaders().getFirst(name);
        return (value == null || value.isBlank()) ? null : value;
    }

    private static String resolveIp(ServerHttpRequest request) {
        String forwarded = request.getHeaders().getFirst("x-forwarded-for");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.lastIndexOf(',');
            return (comma >= 0 ? forwarded.substring(comma + 1) : forwarded).trim();
        }
        return request.getRemoteAddress() == null ? null
                : request.getRemoteAddress().getAddress().getHostAddress();
    }

    private static String hashDeviceId(String userAgent) {
        String base = (userAgent == null || userAgent.isBlank()) ? "unknown-device" : userAgent;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(base.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception error) {
            return "device-unknown";
        }
    }
}
