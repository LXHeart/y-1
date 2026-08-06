package com.grassland.intelligence.ai;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AI provider base-url 校验（SSRF 第一道闸门，复刻 legacy {@code server/src/lib/provider-url.ts}）。
 *
 * <p>本 slice 平台默认 base-url 来自环境变量（ops 受信、非用户输入），故校验聚焦：
 * <ul>
 *   <li>结构：非空、http/https、无 userinfo、主机存在；</li>
 *   <li>IP 字面量私有地址拒绝（{@code 127.0.0.1}、{@code 10/8}、{@code 169.254/16}、{@code ::1} 等）——
 *       纯字面解析，<b>不触发 DNS</b>，启动期/离线/测试皆安全。</li>
 * </ul>
 *
 * <p>{@link #validate(String)} 保留给受信的平台配置；用户 BYOK 必须走严格的保存/执行校验，
 * 解析全部 DNS 地址并配合固定地址连接，拒绝私网目标与 DNS rebinding。
 */
public final class ProviderUrlGuard {

    private static final Set<String> ALLOWED_PROTOCOLS = Set.of("http", "https");
    private static final Pattern IPV4 = Pattern.compile("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$");

    private ProviderUrlGuard() {}

    public static URI validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("AI base-url 格式非法");
        }
        URI url;
        try {
            url = URI.create(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("AI base-url 格式非法");
        }
        String scheme = url.getScheme();
        if (scheme == null || !ALLOWED_PROTOCOLS.contains(scheme.toLowerCase())) {
            throw new IllegalArgumentException("AI base-url 必须是 http/https");
        }
        if (url.getUserInfo() != null && !url.getUserInfo().isBlank()) {
            throw new IllegalArgumentException("AI base-url 不得含凭据");
        }
        String host = url.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("AI base-url 缺主机");
        }
        if (isPrivateIpLiteral(host)) {
            throw new IllegalArgumentException("AI base-url 指向内网/私有/环回地址，已拒绝");
        }
        return url;
    }

    /** 保存用户 BYOK provider 时执行严格校验，并固定当前全部公网 DNS 地址。 */
    public static URI validateByokForStorage(String raw, DnsPinningResolver resolver) {
        URI url = validateByokStructure(raw);
        Set<String> addresses = resolvePublicAddresses(url.getHost(), resolver);
        resolver.pinDomain(url.getHost(), addresses);
        return url;
    }

    /** 执行 BYOK provider 请求前重新校验全部地址与已固定集合完全一致。 */
    public static URI validateByokForExecution(String raw, DnsPinningResolver resolver) {
        URI url = validateByokStructure(raw);
        Set<String> addresses = resolvePublicAddresses(url.getHost(), resolver);
        Set<String> pinned = resolver.getPinnedIps(url.getHost());
        if (pinned.isEmpty()) {
            // 进程重启后内存 pin 会丢失；首次执行以当前全部公网地址重新建立 pin。
            resolver.pinDomain(url.getHost(), addresses);
        } else if (!pinned.equals(addresses)) {
            throw new IllegalArgumentException("AI base-url DNS 地址与固定记录不一致，已拒绝");
        }
        return url;
    }

    private static URI validateByokStructure(String raw) {
        URI url = parse(raw);
        if (!"https".equalsIgnoreCase(url.getScheme())) {
            throw new IllegalArgumentException("BYOK AI base-url 必须使用 HTTPS");
        }
        if (url.getUserInfo() != null && !url.getUserInfo().isBlank()) {
            throw new IllegalArgumentException("AI base-url 不得含凭据");
        }
        String host = normalizeHost(url.getHost());
        if (host.isBlank()) {
            throw new IllegalArgumentException("AI base-url 缺主机");
        }
        if ("localhost".equals(host) || host.endsWith(".localhost")) {
            throw new IllegalArgumentException("AI base-url 指向内网/私有/环回地址，已拒绝");
        }
        return url;
    }

    private static Set<String> resolvePublicAddresses(String host, DnsPinningResolver resolver) {
        try {
            String normalizedHost = normalizeHost(host);
            Set<String> addresses = isIpLiteral(normalizedHost)
                    ? Set.of(InetAddress.getByName(normalizedHost).getHostAddress())
                    : resolver.resolveAll(normalizedHost);
            if (addresses.isEmpty()) {
                throw new IllegalArgumentException("AI base-url DNS 未返回地址，已拒绝");
            }
            for (String address : addresses) {
                InetAddress parsed = InetAddress.getByName(address);
                if (isPrivateAddress(parsed)) {
                    throw new IllegalArgumentException("AI base-url 指向内网/私有/环回地址，已拒绝");
                }
            }
            return addresses;
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("AI base-url DNS 解析失败，已拒绝", e);
        }
    }

    private static boolean isIpLiteral(String host) {
        return IPV4.matcher(host).matches() || host.indexOf(':') >= 0;
    }

    private static URI parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("AI base-url 格式非法");
        }
        try {
            return URI.create(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("AI base-url 格式非法");
        }
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.startsWith("[") && normalized.endsWith("]")
                ? normalized.substring(1, normalized.length() - 1)
                : normalized;
    }

    /**
     * 仅对 IP 字面量做私有地址判定（字面解析，无 DNS——离线/启动期安全）。域名返回 false（视为可信平台配置）。
     * IPv6 字面量在 URI 中以 {@code [::1]} 形式出现，先剥方括号；其含 {@code :} 即判为字面量。
     */
    private static boolean isPrivateIpLiteral(String host) {
        String h = (host.startsWith("[") && host.endsWith("]"))
                ? host.substring(1, host.length() - 1)
                : host;
        boolean literal = IPV4.matcher(h).matches() || h.indexOf(':') >= 0;
        if (!literal) {
            return false;
        }
        try {
            for (InetAddress addr : InetAddress.getAllByName(h)) {
                if (isPrivateAddress(addr)) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    static boolean isPrivateAddress(InetAddress a) {
        if (a.isAnyLocalAddress()         // 0.0.0.0 / ::
                || a.isLoopbackAddress()  // 127.0.0.0/8 / ::1
                || a.isLinkLocalAddress() // 169.254.0.0/16 / fe80::/10
                || a.isSiteLocalAddress() // 10/8、172.16/12、192.168/16
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = a.getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 0
                    || (first == 100 && second >= 64 && second <= 127) // carrier-grade NAT
                    || (first == 198 && (second == 18 || second == 19)); // benchmark network
        }
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc; // IPv6 unique-local fc00::/7
    }
}
