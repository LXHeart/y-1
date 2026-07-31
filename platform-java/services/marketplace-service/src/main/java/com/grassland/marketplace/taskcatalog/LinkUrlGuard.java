package com.grassland.marketplace.taskcatalog;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * content_url SSRF 校验（Verification v1）。
 *
 * <p>移植 intelligence {@code ai/ProviderUrlGuard} 的私有 IP 字面量拒绝，并补 DNS 解析后 resolved-IP
 * 私有地址检查——{@code content_url} 是推荐官输入（DNS rebinding 真实攻击面），区别于 ProviderUrlGuard
 * 只挡 env 受信的 provider base-url（域名不解析）。校验：非空、http/https、无 userinfo、主机存在；
 * IP 字面量私有/环回/链路本地/多播 → 拒绝；域名解析后任一地址私有 → 拒绝。
 *
 * <p>校验失败抛 {@link IllegalArgumentException}，调用方（{@link LinkReachabilityChecker}）转成 inconclusive
 * 核验态（detail 带原因），不把整个核验端点 400 掉——商家对 inconclusive 自行决策。
 */
final class LinkUrlGuard {

    private static final Set<String> ALLOWED_PROTOCOLS = Set.of("http", "https");
    private static final Pattern IPV4 = Pattern.compile("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$");

    private LinkUrlGuard() {}

    static URI validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("链接为空");
        }
        URI url;
        try {
            url = URI.create(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("链接格式非法");
        }
        String scheme = url.getScheme();
        if (scheme == null || !ALLOWED_PROTOCOLS.contains(scheme.toLowerCase())) {
            throw new IllegalArgumentException("仅支持 http/https 链接");
        }
        if (url.getUserInfo() != null && !url.getUserInfo().isBlank()) {
            throw new IllegalArgumentException("链接不得含凭据");
        }
        String host = url.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("链接缺主机");
        }
        if (isPrivateIpLiteral(host) || resolvesToPrivate(host)) {
            throw new IllegalArgumentException("内网/私有地址不可核验");
        }
        return url;
    }

    /** IP 字面量私有地址判定（字面解析，含 IPv6 [::1] 剥方括号）。域名 → false（交 resolvesToPrivate）。 */
    private static boolean isPrivateIpLiteral(String host) {
        String h = stripBrackets(host);
        if (!isLiteral(h)) {
            return false;
        }
        try {
            for (InetAddress addr : InetAddress.getAllByName(h)) {
                if (isPrivate(addr)) {
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            return false;
        }
        return false;
    }

    /** 域名解析后任一地址私有 → true（DNS rebinding 防护；literal 跳过，已由 isPrivateIpLiteral 覆盖）。 */
    private static boolean resolvesToPrivate(String host) {
        String h = stripBrackets(host);
        if (isLiteral(h)) {
            return false;
        }
        try {
            for (InetAddress addr : InetAddress.getAllByName(h)) {
                if (isPrivate(addr)) {
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            return false;  // 解析失败交给后续 HTTP 探测 → inconclusive
        }
        return false;
    }

    private static boolean isLiteral(String h) {
        return IPV4.matcher(h).matches() || h.indexOf(':') >= 0;
    }

    private static String stripBrackets(String host) {
        return (host.startsWith("[") && host.endsWith("]")) ? host.substring(1, host.length() - 1) : host;
    }

    private static boolean isPrivate(InetAddress a) {
        return a.isAnyLocalAddress()      // 0.0.0.0 / ::
                || a.isLoopbackAddress()  // 127.0.0.0/8 / ::1
                || a.isLinkLocalAddress() // 169.254.0.0/16 / fe80::/10
                || a.isSiteLocalAddress() // 10/8、172.16/12、192.168/16
                || a.isMulticastAddress();
    }
}
