package com.grassland.database;

import java.net.URI;

/**
 * DATABASE_URL（postgres://user:pass@host[:port]/db[?query]）→ JDBC 连接三元组。
 *
 * <p>原五服务 DataSourceConfig 各持一份逐字相同的解析（2026-08-20 下沉）。要点：
 * <ul>
 *   <li>java.net.URI 以最后一个 {@code @} 分隔 userInfo 与 host，含 {@code @} 的密码
 *       （如 {@code Aa@111111}）能正确归属 userInfo。</li>
 *   <li>兼容无显式端口的 URL（neon pooler）——省略端口段，PG JDBC 默认 5432，不写出 {@code :-1}。</li>
 *   <li>保留查询串中的 {@code sslmode}（neon 要求 SSL），丢弃 JDBC 不识别的 {@code channel_binding}
 *       （JDBC 用 {@code channelBinding}，如需可另行配置）。</li>
 * </ul>
 */
public final class DatabaseUrls {

    private DatabaseUrls() {}

    public static JdbcParts parse(String databaseUrl) {
        int schemeEnd = databaseUrl.indexOf("://");
        String rest = schemeEnd >= 0 ? databaseUrl.substring(schemeEnd + 3) : databaseUrl;
        // 手工以最后一个 @ 分割 userInfo/host：java.net.URI 遇到未转义的双 @ 会整体解析失败
        // （host/userInfo 均返回 null），含 @ 的密码会静默产出垃圾 jdbcUrl——原五份拷贝的注释
        // 声称此场景已处理，实际从未生效，2026-08-20 下沉时修正。
        int userInfoEnd = rest.lastIndexOf('@');
        String userInfo = userInfoEnd >= 0 ? rest.substring(0, userInfoEnd) : null;
        URI uri = URI.create("http://" + (userInfoEnd >= 0 ? rest.substring(userInfoEnd + 1) : rest));
        String user = "";
        String password = "";
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            user = colon < 0 ? userInfo : userInfo.substring(0, colon);
            password = colon < 0 ? "" : userInfo.substring(colon + 1);
        }
        String portPart = uri.getPort() > 0 ? ":" + uri.getPort() : "";
        String queryPart = toJdbcQuery(uri.getRawQuery());
        String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + portPart + uri.getPath() + queryPart;
        return new JdbcParts(jdbcUrl, user, password);
    }

    /** 保留 sslmode 等 JDBC 识别的查询参数，丢弃 channel_binding（JDBC 用 channelBinding，此名不识别）。 */
    private static String toJdbcQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        StringBuilder qb = new StringBuilder();
        String sep = "?";
        for (String param : rawQuery.split("&")) {
            if (param.startsWith("channel_binding")) {
                continue;
            }
            qb.append(sep).append(param);
            sep = "&";
        }
        return qb.toString();
    }

    public record JdbcParts(String jdbcUrl, String user, String password) {}
}
