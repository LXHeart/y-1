package com.grassland.intelligence.creationstudio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual isolated databases, independent of the application IT database and all
 * local developer data.
 */
@Testcontainers
class CreationStudioMigrationIT {
	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
	private static final List<String> TABLES = List.of("creation_source_document", "creation_text_proposal",
			"creation_visual_plan", "creation_visual_plan_revision", "creation_visual_quote", "creation_visual_item",
			"creation_visual_artifact", "creation_studio_apply", "creation_export", "creation_wechat_account",
			"creation_wechat_draft_sync", "creation_wechat_media_mapping");

	private String database() throws Exception {
		String name = "task101_" + UUID.randomUUID().toString().replace("-", "");
		try (var connection = connect(POSTGRES.getJdbcUrl()); var statement = connection.createStatement()) {
			statement.execute("CREATE DATABASE " + name);
		}
		String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + name;
		// Identity-owned prerequisites, exactly as in IntelligenceItSupport. No
		// intelligence tables exist yet.
		try (var connection = connect(url); var statement = connection.createStatement()) {
			statement.execute(
					"CREATE TABLE app_users(id uuid PRIMARY KEY,email text NOT NULL UNIQUE,password_hash text NOT NULL,"
							+ "display_name text,role text NOT NULL DEFAULT 'user',status text NOT NULL DEFAULT 'active',"
							+ "created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),last_login_at timestamptz)");
			statement.execute(
					"CREATE TABLE user_settings(id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES app_users(id),"
							+ "settings_type text NOT NULL,settings_json jsonb NOT NULL,version integer NOT NULL DEFAULT 1,"
							+ "created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),"
							+ "CONSTRAINT user_settings_type_check CHECK(settings_type IN ('analysis','homepage','image-review-style')),"
							+ "CONSTRAINT user_settings_unique_user_type UNIQUE(user_id,settings_type))");
		}
		return url;
	}
	private Connection connect(String url) throws Exception {
		return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
	}
	private Flyway flyway(String url, String target) {
		return Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").table("intelligence_flyway_schema").baselineOnMigrate(true)
				.baselineVersion("0").target(target).load();
	}

	@Test
	void emptyDatabaseMigratesTo84AndRejectsInvalidStates() throws Exception {
		String url = database();
		Flyway migrations = flyway(url, "84");
		assertThat(migrations.migrate().migrationsExecuted).isGreaterThanOrEqualTo(84);
		migrations.validate();
		try (var connection = connect(url)) {
			for (String table : TABLES)
				assertThat(scalar(connection, "SELECT to_regclass('" + table + "')")).isEqualTo(table);
			assertThat(scalar(connection,
					"SELECT max(version::int)::text FROM intelligence_flyway_schema WHERE type='SQL'")).isEqualTo("84");
			String id = UUID.randomUUID().toString();
			assertThatThrownBy(() -> connection.createStatement().execute(
					"INSERT INTO creation_wechat_account(id,owner_account_id,display_name,app_id,state,version)"
							+ " VALUES ('" + id + "','fixture','invalid','wx0000000000000001','published',1)"))
					.hasMessageContaining("check constraint");
			assertThatThrownBy(() -> connection.createStatement()
					.execute("INSERT INTO creation_export(id,owner_account_id,draft_id,version,request_id,payload_hash,"
							+ "format,theme,include_title,cite_external_links,state) VALUES ('" + id + "','fixture','"
							+ id + "',0,'request',repeat('a',64)," + "'bundle-zip','standard',false,false,'building')"))
					.hasMessageContaining("check constraint");
		}
		assertThat(migrations.migrate().migrationsExecuted).isZero();
	}

	@Test
	void populated76UpgradeAndDdlReplayPreserveLegacyRowsHashesAndChecksums() throws Exception {
		String url = database();
		flyway(url, "76").migrate();
		Map<String, String> before;
		Map<String, String> checksums;
		try (var connection = connect(url); var statement = connection.createStatement()) {
			String draft = UUID.randomUUID().toString(), media = UUID.randomUUID().toString(),
					board = UUID.randomUUID().toString();
			statement.execute(
					"INSERT INTO media_reference(id,owner_account_id,purpose,object_key,mime_type,status,checksum)"
							+ " VALUES ('" + media
							+ "','fixture','reference','migration/fixture.png','image/png','active',repeat('a',64))");
			statement.execute(
					"INSERT INTO creation_draft(id,owner_account_id,source_type,title,platform,content_form,content,workspace_json)"
							+ " VALUES ('" + draft
							+ "','fixture','independent','V76 原稿','xiaohongshu','graphic','旧正文：人均 68 元。',"
							+ "'{\"schemaVersion\":1,\"capability\":\"article\",\"inputs\":{\"cards\":{\"cards\":[{\"cardId\":\"legacy-card\"}]}},"
							+ "\"resultRefs\":[{\"refType\":\"media\",\"id\":\"" + media + "\"}]}')");
			statement.execute(
					"INSERT INTO card_series_operation(id,owner_account_id,request_id,request_digest,status,result)"
							+ " VALUES (gen_random_uuid(),'fixture','legacy-request','legacy-hash','succeeded','{\"mediaId\":\""
							+ media + "\"}')");
			statement.execute("INSERT INTO video_storyboard(id,account_id,target_duration_seconds,request_payload)"
					+ " VALUES ('" + board + "','fixture',15,'{\"title\":\"旧视频\"}')");
			statement.execute("INSERT INTO creation_canvas_document(id,draft_id,account_id,document)"
					+ " VALUES(gen_random_uuid(),'" + draft
					+ "','fixture','{\"schemaVersion\":1,\"nodes\":[{\"id\":\"legacy-node\",\"mediaId\":\"" + media
					+ "\"}]}')");
			before = legacySnapshot(connection);
			checksums = checksums(connection);
		}
		Flyway migrations = flyway(url, "84");
		assertThat(migrations.migrate().migrationsExecuted).isEqualTo(8);
		try (var connection = connect(url); var statement = connection.createStatement()) {
			assertThat(legacySnapshot(connection)).isEqualTo(before);
			assertThat(checksums(connection)).containsAllEntriesOf(checksums);
			for (var migration : migrations.info().applied()) {
				if (migration.getVersion() == null || migration.getVersion()
						.compareTo(org.flywaydb.core.api.MigrationVersion.fromVersion("77")) < 0)
					continue;
				try (var stream = getClass().getClassLoader()
						.getResourceAsStream("db/migration/" + migration.getScript())) {
					assertThat(stream).isNotNull();
					statement.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
				}
			}
			assertThat(legacySnapshot(connection)).isEqualTo(before);
			assertThat(checksums(connection)).containsAllEntriesOf(checksums);
			assertThat(scalar(connection,
					"SELECT api_version::text FROM card_series_operation WHERE request_id='legacy-request'"))
					.isEqualTo("1");
			assertThat(scalar(connection,
					"SELECT count(*)::text FROM pg_constraint WHERE conname='uq_creation_wechat_media'"))
					.isEqualTo("0");
		}
		migrations.validate();
	}

	private static Map<String, String> checksums(Connection connection) throws Exception {
		Map<String, String> result = new LinkedHashMap<>();
		try (var statement = connection.createStatement();
				var rows = statement.executeQuery(
						"SELECT version, checksum FROM intelligence_flyway_schema WHERE type='SQL' AND version::int <= 76 ORDER BY installed_rank")) {
			while (rows.next())
				result.put(rows.getString(1), rows.getString(2));
		}
		return result;
	}
	private static Map<String, String> legacySnapshot(Connection connection) throws Exception {
		Map<String, String> result = new LinkedHashMap<>();
		for (String table : List.of("creation_draft", "creation_canvas_document", "video_storyboard",
				"media_reference"))
			result.put(table, scalar(connection,
					"SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY id)::text,'')) FROM " + table + " t"));
		result.put("oldOperations",
				scalar(connection, "SELECT md5(jsonb_agg(jsonb_build_object('id',id,'owner',owner_account_id,"
						+ "'request',request_id,'digest',request_digest,'status',status,'result',result) ORDER BY id)::text) FROM card_series_operation"));
		return result;
	}
	private static String scalar(Connection connection, String sql) throws Exception {
		try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
			rows.next();
			return rows.getString(1);
		}
	}
}
