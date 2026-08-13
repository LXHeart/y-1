package com.grassland.bootstrap;

import java.net.URI;

record DatabaseUrl(String jdbcUrl, String user, String password) {

    static DatabaseUrl parse(String databaseUrl) {
        int schemeEnd = databaseUrl.indexOf("://");
        String rest = schemeEnd >= 0 ? databaseUrl.substring(schemeEnd + 3) : databaseUrl;
        URI uri = URI.create("http://" + rest);
        if (uri.getHost() == null || uri.getPath() == null || uri.getPath().length() < 2) {
            throw new IllegalArgumentException("DATABASE_URL must include host and database name");
        }

        String user = "";
        String password = "";
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            user = colon < 0 ? userInfo : userInfo.substring(0, colon);
            password = colon < 0 ? "" : userInfo.substring(colon + 1);
        }

        String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
        String query = jdbcQuery(uri.getRawQuery());
        return new DatabaseUrl(
                "jdbc:postgresql://" + uri.getHost() + port + uri.getPath() + query,
                user,
                password);
    }

    private static String jdbcQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder("?");
        String separator = "";
        for (String parameter : rawQuery.split("&")) {
            if (parameter.startsWith("channel_binding")) {
                continue;
            }
            result.append(separator).append(parameter);
            separator = "&";
        }
        return result.length() == 1 ? "" : result.toString();
    }
}
