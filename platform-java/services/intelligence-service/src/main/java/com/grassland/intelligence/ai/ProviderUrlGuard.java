package com.grassland.intelligence.ai;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
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
 * <p>域名视为可信（不做 DNS 解析）——攻击面限于 env 注入。用户可传 URL 的 BYOK 场景再补
 * pinned-DNS + {@code TRUSTED_PUBLIC_API_SUFFIXES} 受信后缀白名单（legacy 全貌），留后续 slice（卡 D-11）。
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

    private static boolean isPrivateAddress(InetAddress a) {
        return a.isAnyLocalAddress()      // 0.0.0.0 / ::
                || a.isLoopbackAddress()  // 127.0.0.0/8 / ::1
                || a.isLinkLocalAddress() // 169.254.0.0/16 / fe80::/10
                || a.isSiteLocalAddress() // 10/8、172.16/12、192.168/16
                || a.isMulticastAddress();
    }
}
