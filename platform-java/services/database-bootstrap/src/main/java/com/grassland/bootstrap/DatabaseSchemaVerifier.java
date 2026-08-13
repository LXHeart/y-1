package com.grassland.bootstrap;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

@Component
final class DatabaseSchemaVerifier {

    private static final Map<String, Map<String, Column>> EXPECTED = Map.of(
            "app_users", columns(
                    entry("id", "uuid", false),
                    entry("email", "text", false),
                    entry("password_hash", "text", false),
                    entry("display_name", "text", true),
                    entry("role", "text", false),
                    entry("status", "text", false),
                    entry("created_at", "timestamptz", false),
                    entry("updated_at", "timestamptz", false),
                    entry("last_login_at", "timestamptz", true)),
            "session", columns(
                    entry("sid", "varchar", false),
                    entry("sess", "json", false),
                    entry("expire", "timestamp", false)),
            "user_settings", columns(
                    entry("id", "uuid", false),
                    entry("user_id", "uuid", false),
                    entry("settings_type", "text", false),
                    entry("settings_json", "jsonb", false),
                    entry("version", "int4", false),
                    entry("created_at", "timestamptz", false),
                    entry("updated_at", "timestamptz", false)),
            "email_verification_codes", columns(
                    entry("id", "uuid", false),
                    entry("email", "text", false),
                    entry("code", "text", false),
                    entry("used", "bool", false),
                    entry("expires_at", "timestamptz", false),
                    entry("created_at", "timestamptz", false)));

    private static final Map<String, Map<String, String[]>> REQUIRED_CONSTRAINTS = Map.of(
            "app_users", Map.of(
                    "app_users_pkey", fragments("primary key", "(id)"),
                    "app_users_email_key", fragments("unique", "(email)")),
            "session", Map.of(
                    "session_pkey", fragments("primary key", "(sid)")),
            "user_settings", Map.of(
                    "user_settings_pkey", fragments("primary key", "(id)"),
                    "user_settings_user_id_fkey", fragments(
                            "foreign key", "(user_id)", "references app_users(id)", "on delete cascade"),
                    "user_settings_unique_user_type", fragments("unique", "(user_id, settings_type)"),
                    "user_settings_type_check", fragments(
                            "check", "analysis", "homepage", "image-review-style")),
            "email_verification_codes", Map.of(
                    "email_verification_codes_pkey", fragments("primary key", "(id)")));

    private final DataSource dataSource;

    DatabaseSchemaVerifier(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    DataSource dataSource() {
        return dataSource;
    }

    void verify() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            for (var table : EXPECTED.entrySet()) {
                verifyTable(connection, table.getKey(), table.getValue());
                verifyConstraints(connection, table.getKey(), REQUIRED_CONSTRAINTS.get(table.getKey()));
            }
            verifyAppUserRoleDefault(connection);
        }
    }

    private static void verifyConstraints(
            Connection connection, String table, Map<String, String[]> requiredConstraints) throws SQLException {
        Map<String, String> actual = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT constraint_name, pg_get_constraintdef(pg_constraint.oid) AS definition
                FROM information_schema.table_constraints
                JOIN pg_constraint ON pg_constraint.conname = constraint_name
                WHERE table_schema = 'public' AND table_name = ?
                  AND pg_constraint.conrelid = ('public.' || ?)::regclass
                """)) {
            statement.setString(1, table);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    actual.put(rows.getString("constraint_name"),
                            rows.getString("definition").toLowerCase(Locale.ROOT));
                }
            }
        }

        requiredConstraints.forEach((name, fragments) -> {
            String definition = actual.get(name);
            if (definition == null) {
                throw new IllegalStateException("database constraint is missing: public." + table + "." + name);
            }
            for (String fragment : fragments) {
                if (!definition.contains(fragment)) {
                    throw new IllegalStateException("database constraint drift at public." + table + "." + name
                            + ": expected fragment '" + fragment + "', found " + definition);
                }
            }
        });
    }

    private static void verifyAppUserRoleDefault(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_default FROM information_schema.columns
                WHERE table_schema='public' AND table_name='app_users' AND column_name='role'
                """)) {
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getString(1) == null || !rows.getString(1).contains("user")) {
                    throw new IllegalStateException("database schema drift at public.app_users.role default");
                }
            }
        }
    }

    private static void verifyTable(Connection connection, String table, Map<String, Column> expected)
            throws SQLException {
        Map<String, Column> actual = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name, udt_name, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                """)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    actual.put(rows.getString("column_name"), new Column(
                            rows.getString("udt_name"),
                            "YES".equals(rows.getString("is_nullable"))));
                }
            }
        }

        if (actual.isEmpty()) {
            throw new IllegalStateException("database bootstrap table is missing: public." + table);
        }
        for (var column : expected.entrySet()) {
            Column found = actual.get(column.getKey());
            if (!column.getValue().equals(found)) {
                throw new IllegalStateException("database schema drift at public." + table + "."
                        + column.getKey() + ": expected " + column.getValue() + ", found " + found);
            }
        }
    }

    @SafeVarargs
    private static Map<String, Column> columns(Map.Entry<String, Column>... entries) {
        return Map.ofEntries(entries);
    }

    private static Map.Entry<String, Column> entry(String name, String type, boolean nullable) {
        return Map.entry(name, new Column(type, nullable));
    }

    private static String[] fragments(String... values) {
        return values;
    }

    private record Column(String type, boolean nullable) {}
}
