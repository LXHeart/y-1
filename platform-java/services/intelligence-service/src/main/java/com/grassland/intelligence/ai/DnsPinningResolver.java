package com.grassland.intelligence.ai;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DNS Pinning 解析器（GL-P3-AI-001 Phase 2）。
 *
 * <p>防止 AI provider 请求的 DNS 劫持攻击：
 * <ul>
 *   <li>启动时解析受信任域名并固定 IP 地址</li>
 *   <li>运行时验证请求目标是否与预定义 IP 匹配</li>
 *   <li>拒绝未预注册的域名或 IP 地址变化</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 *   DnsPinningResolver resolver = DnsPinningResolver.create();
 *   resolver.pinDomain("api.openai.com", Set.of("104.16.123.45"));
 *   boolean safe = resolver.isSafeTarget("https://api.openai.com/v1/chat");
 * }</pre>
 */
public final class DnsPinningResolver {

    private static final Logger logger = LoggerFactory.getLogger(DnsPinningResolver.class);

    /** 域名 → 固定 IP 地址集合（启动时解析或配置） */
    private final Map<String, Set<String>> pinnedIps = new ConcurrentHashMap<>();

    /** 域名 → 首次解析时间（用于监控） */
    private final Map<String, Long> pinnedTime = new ConcurrentHashMap<>();

    private final HostResolver hostResolver;

    private DnsPinningResolver(HostResolver hostResolver) {
        this.hostResolver = hostResolver;
    }

    /** 创建默认实例（不含任何 pinning，需显式调用 pinDomain）。 */
    public static DnsPinningResolver create() {
        return new DnsPinningResolver(InetAddress::getAllByName);
    }

    /** 创建使用指定 DNS 解析函数的实例，供确定性测试和受控运行环境使用。 */
    public static DnsPinningResolver create(HostResolver hostResolver) {
        if (hostResolver == null) {
            throw new IllegalArgumentException("hostResolver is required");
        }
        return new DnsPinningResolver(hostResolver);
    }

    /**
     * 创建实例并从环境变量加载受信任域名。
     *
     * @param trustedDomainsEnv 环境变量值，格式：{@code domain1=ip1,ip2;domain2=ip3}
     *                        例如：{@code api.openai.com=104.16.123.45;dashscope.aliyuncs.com=47.96.23.1}
     */
    public static DnsPinningResolver fromEnv(String trustedDomainsEnv) {
        DnsPinningResolver resolver = create();
        if (trustedDomainsEnv != null && !trustedDomainsEnv.isBlank()) {
            String[] domains = trustedDomainsEnv.split(";");
            for (String domainEntry : domains) {
                String[] parts = domainEntry.split("=", 2);
                if (parts.length == 2) {
                    String domain = parts[0].trim();
                    String[] ips = parts[1].split(",");
                    resolver.pinDomain(domain, Set.of(ips));
                }
            }
        }
        return resolver;
    }

    /**
     * 固定域名的 IP 地址。
     *
     * @param domain 域名（如 {@code api.openai.com}）
     * @param ipAddresses 该域名解析出的 IP 地址集合（支持多 IP 负载均衡）
     */
    public void pinDomain(String domain, Set<String> ipAddresses) {
        String normalizedDomain = normalizeHost(domain);
        Set<String> normalizedIps = ipAddresses == null ? Set.of() : ipAddresses.stream()
                .map(String::trim)
                .filter(ip -> !ip.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (normalizedDomain.isBlank() || normalizedIps.isEmpty()) {
            throw new IllegalArgumentException("domain and ipAddresses are required");
        }
        pinnedIps.put(normalizedDomain, normalizedIps);
        pinnedTime.put(normalizedDomain, System.currentTimeMillis());
        logger.info("DNS pinned: {} -> {}", normalizedDomain, normalizedIps);
    }

    /**
     * 固定域名（自动 DNS 解析一次）。
     *
     * <p>解析结果必须由调用方完成公网地址校验后才能用于用户 BYOK。
     *
     * @param domain 域名
     * @return 是否成功解析并固定
     */
    public boolean pinDomainByDns(String domain) {
        try {
            Set<String> ips = resolveAll(domain);
            if (ips.isEmpty()) {
                logger.warn("DNS resolve returned empty for domain: {}", domain);
                return false;
            }
            pinDomain(domain, ips);
            return true;
        } catch (UnknownHostException e) {
            logger.error("DNS resolve failed for domain: {}", domain, e);
            return false;
        }
    }

    /**
     * 检查目标 URL 是否安全（域名已 pin 且 IP 在白名单内）。
     *
     * @param targetUrl 目标 URL（如 {@code https://api.openai.com/v1/chat}）
     * @return 是否安全
     */
    public boolean isSafeTarget(String targetUrl) {
        if (targetUrl == null || targetUrl.isBlank()) {
            return false;
        }

        String host = extractHost(targetUrl);
        if (host == null) {
            return false;
        }

        Set<String> allowedIps = pinnedIps.get(normalizeHost(host));
        if (allowedIps == null || allowedIps.isEmpty()) {
            logger.warn("Domain not pinned: {}", host);
            return false;
        }

        // 验证当前解析的 IP 是否仍在白名单内
        try {
            Set<String> currentIps = resolveAll(host);
            if (!allowedIps.equals(currentIps)) {
                logger.error("DNS pinning violation: {} resolved to {}, allowed {}", host, currentIps, allowedIps);
                return false;
            }
            return !currentIps.isEmpty();
        } catch (UnknownHostException e) {
            logger.error("DNS resolve failed for pinned domain: {}", host, e);
            return false;
        }
    }

    /**
     * 获取域名的固定 IP 地址集合（用于监控/审计）。
     *
     * @param domain 域名
     * @return IP 地址集合；未 pin 返回空集合
     */
    public Set<String> getPinnedIps(String domain) {
        return pinnedIps.getOrDefault(normalizeHost(domain), Set.of());
    }

    /**
     * 获取所有已 pin 的域名。
     *
     * @return 域名集合
     */
    public Set<String> getPinnedDomains() {
        return pinnedIps.keySet();
    }

    /** 从 URL 提取主机名。 */
    private String extractHost(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                logger.warn("Invalid URL, no host: {}", url);
                return null;
            }
            return host;
        } catch (Exception e) {
            logger.warn("Failed to parse URL: {}", url, e);
            return null;
        }
    }

    /** 返回已固定的地址；调用方可据此构造不再触发 DNS 的实际连接。 */
    public Set<InetAddress> getPinnedAddresses(String domain) {
        Set<InetAddress> addresses = new LinkedHashSet<>();
        for (String ip : getPinnedIps(domain)) {
            try {
                addresses.add(InetAddress.getByName(ip));
            } catch (UnknownHostException e) {
                throw new IllegalStateException("Invalid pinned IP address", e);
            }
        }
        return Set.copyOf(addresses);
    }

    /** 解析域名的全部地址并去重。 */
    public Set<String> resolveAll(String domain) throws UnknownHostException {
        Set<String> result = new LinkedHashSet<>();
        for (InetAddress address : hostResolver.resolve(normalizeHost(domain))) {
            result.add(address.getHostAddress());
        }
        return Set.copyOf(result);
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.startsWith("[") && value.endsWith("]")
                ? value.substring(1, value.length() - 1)
                : value;
    }

    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
