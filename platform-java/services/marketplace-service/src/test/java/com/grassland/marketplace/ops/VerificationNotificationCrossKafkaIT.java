package com.grassland.marketplace.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.grassland.identity.IdentityServiceApplication;
import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.TestAssertionHelper;
import com.grassland.identity.assertion.TestAssertionHelper.KeySpec;
import com.grassland.marketplace.MarketplaceItSupport;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 跨服务通知 e2e（GL-P2-ADMIN-004 后续）：marketplace 人工改判 → outbox → 真实 Kafka （生产 topic
 * 名 {@code grassland.marketplace.events}）→ identity 真实 external consumer →
 * 站内通知落库。此前两侧各有单服务 Kafka IT（publisher 半段 / consumer 半段），但两半段之间的 wire 契约（topic
 * 名、信封字段、payload 收件人键）没有任何测试锁住——本 IT 在同一 JVM 内启动 两个完整 Spring 上下文（共享一个
 * testcontainers Kafka + Postgres，与生产同库表名隔离口径） 补上这条接缝。
 *
 * <p>
 * 刻意**不**继承 {@code MarketplaceItSupport}：@DynamicPropertySource 的同名键由父类覆盖子类
 * （Spring 既定语义），基座的 {@code marketplace.outbox.enabled=false} 会压掉本类的 true—— 而真实
 * publisher 轮询正是被测链路的前半段。容器复用基座的 public static 单例，属性自注册。
 *
 * <p>
 * identity 侧用 {@link org.springframework.boot.builder.SpringApplicationBuilder}
 * 手动启动（属性经 run(args) 以命令行参数注入， 避免被 application.yml 反向覆盖；classpath 上 marketplace
 * 的 application.yml 排在前面会被 identity 读到，故 temporal 自动配置需显式排除、两侧 Flyway locations
 * 钉到各自 filesystem 目录）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VerificationNotificationCrossKafkaIT {

	private static final Duration AWAIT = Duration.ofSeconds(45);
	/** 必须用生产 topic 名——跨服务契约（identity 消费者订阅的默认名）正是被测对象。 */
	private static final String MARKETPLACE_TOPIC = "grassland.marketplace.events";
	private static final String GROUP = "identity-notif-cross-kafka-it";

	private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.1"));

	static {
		KAFKA.start();
	}

	private static ConfigurableApplicationContext identityApp;
	private static Path unpackedMigrationsDir;

	@LocalServerPort
	private int port;

	@Autowired
	private DatabaseClient db;

	@DynamicPropertySource
	static void marketplaceProps(DynamicPropertyRegistry r) {
		String dbUrl = "postgresql://" + MarketplaceItSupport.POSTGRES.getUsername() + ":"
				+ MarketplaceItSupport.POSTGRES.getPassword() + "@" + MarketplaceItSupport.POSTGRES.getHost() + ":"
				+ MarketplaceItSupport.POSTGRES.getMappedPort(5432) + "/"
				+ MarketplaceItSupport.POSTGRES.getDatabaseName();
		r.add("marketplace.datasource.from-database-url", () -> "true");
		r.add("DATABASE_URL", () -> dbUrl);
		r.add("management.server.port", () -> "0");
		r.add("identity-assertion.enabled", () -> "true");
		TestAssertionHelper.registerServiceKeyring(r, "marketplace");
		r.add("object-storage.enabled", () -> "false");
		r.add("marketplace.reconciliation.dispatcher-enabled", () -> "false");
		r.add("marketplace.contest.dispatcher-enabled", () -> "false");
		r.add("marketplace.commerce.dispatcher-enabled", () -> "false");
		r.add("marketplace.settlement.day-seconds", () -> "1");
		r.add("spring.temporal.test-server.enabled", () -> "true");
		r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
		// 本类专属：真实 publisher 前半段是被测对象。
		r.add("marketplace.outbox.enabled", () -> "true");
		r.add("marketplace.outbox.topic", () -> MARKETPLACE_TOPIC);
		r.add("marketplace.outbox.poll-interval-ms", () -> "500");
		// classpath 上有 marketplace 与 identity 两套 db/migration，钉到本模块 resources 的实体目录。
		r.add("marketplace.flyway.locations", () -> "filesystem:" + MarketplaceItSupport.marketplaceMigrationDir());
	}

	@BeforeAll
	static void startIdentityContext() throws Exception {
		Properties admin = new Properties();
		admin.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
		try (AdminClient client = AdminClient.create(admin)) {
			client.createTopics(List.of(new NewTopic(MARKETPLACE_TOPIC, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
		}
		createSharedBootstrapTables();
		// 属性经 run(args) 以真正的命令行参数进入（最高优先级）：SpringApplicationBuilder.properties()
		// 无论 Map 还是 --key=value 形式都落入 defaultProperties（最低优先级），会被 application.yml
		// 的占位默认值反向覆盖（DATABASE_URL/KAFKA_BOOTSTRAP 等在 yml 均有显式条目）。
		identityApp = new org.springframework.boot.builder.SpringApplicationBuilder(IdentityServiceApplication.class)
				.run(commandLineArgs(identityProps()));
	}

	@AfterAll
	static void stopIdentityContext() throws IOException {
		if (identityApp != null) {
			identityApp.close();
			identityApp = null;
		}
		if (unpackedMigrationsDir != null) {
			try (var stream = Files.list(unpackedMigrationsDir)) {
				for (Path file : stream.toList()) {
					Files.deleteIfExists(file);
				}
			}
			Files.deleteIfExists(unpackedMigrationsDir);
			unpackedMigrationsDir = null;
		}
	}

	@Test
	void overrideEventFlowsThroughRealKafkaIntoOwnerNotification() {
		String owner = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String submissionId = seedInconclusiveVerification(owner, recommender);
		insertOwnerAccount(owner);

		client().post().uri("/api/ops/pending-verifications/" + submissionId + "/override")
				.header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "customer_service"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("status", "passed", "note", "人工确认履约合格"))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.status").isEqualTo("passed");

		await().atMost(AWAIT).untilAsserted(() -> {
			Map<String, Object> notification = db.sql("SELECT title, link_path, source_event_id FROM notification"
					+ " WHERE account_id = CAST(:owner AS uuid)" + " AND event_type = 'VerificationOverridden' LIMIT 1")
					.bind("owner", owner)
					.map((row, meta) -> Map.<String, Object>of("title", String.valueOf(row.get("title", String.class)),
							"linkPath", String.valueOf(row.get("link_path", String.class)), "sourceEventId",
							String.valueOf(row.get("source_event_id", String.class))))
					.one().block(Duration.ofSeconds(5));
			assertThat(notification).isNotNull();
			assertThat(notification.get("sourceEventId")).asString().startsWith("VerificationOverridden:");
			assertThat(notification.get("linkPath")).isNotNull();
		});

		// inbox 幂等闸门：该 consumer 对此事件恰好记录一行（event_id 确定性派生自 override id+version）。
		Integer inboxRows = db
				.sql("SELECT COUNT(*) FROM identity_inbox"
						+ " WHERE consumer_name = :consumer AND event_type = 'VerificationOverridden'")
				.bind("consumer", GROUP).map((row, meta) -> row.get(0, Integer.class)).one()
				.block(Duration.ofSeconds(5));
		assertThat(inboxRows).isEqualTo(1);
	}

	private WebTestClient client() {
		// responseTimeout 30s：CI 慢 runner 负载口径（MarketplaceItSupport 同款）。
		return WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
				.responseTimeout(java.time.Duration.ofSeconds(30)).build();
	}

	/** 镜像 MarketplaceItSupport.signWithRole：运营门禁按平台角色判定，非业务身份。 */
	private String signWithRole(String accountId, String role) {
		Instant now = Instant.now();
		return TestAssertionHelper.userSigner("edge-bff", "grassland-marketplace")
				.sign(new IdentityAssertion(accountId, null, "sid-" + accountId, null, null, "cookie-session", "level1",
						null, "r", "t", "grassland-marketplace", now, now.plusSeconds(60), null, null, role));
	}

	private String seedInconclusiveVerification(String owner, String recommender) {
		String taskId = UUID.randomUUID().toString();
		String applicationId = UUID.randomUUID().toString();
		String submissionId = UUID.randomUUID().toString();
		db.sql("INSERT INTO task(id, owner_account_id, organization_id, title, status)"
				+ " VALUES (CAST(:id AS uuid), CAST(:owner AS uuid), CAST(:org AS uuid), 'cross-kafka task', 'published')")
				.bind("id", taskId).bind("owner", owner).bind("org", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(5));
		db.sql("INSERT INTO task_application(id, task_id, recommender_account_id, status, bounty_cents,"
				+ " reputation_level_at_accept, reputation_policy_version_at_accept,"
				+ " settlement_delay_days_at_accept, commission_bonus_bps_at_accept," + " premium_support_at_accept)"
				+ " VALUES (CAST(:id AS uuid), CAST(:task AS uuid), CAST(:rec AS uuid), 'accepted', 0, 1, 1, 2, 0, false)")
				.bind("id", applicationId).bind("task", taskId).bind("rec", recommender).then()
				.block(Duration.ofSeconds(5));
		db.sql("INSERT INTO engagement_submission(id, application_id, recommender_account_id, content_url, status)"
				+ " VALUES (CAST(:id AS uuid), CAST(:app AS uuid), CAST(:rec AS uuid),"
				+ " 'https://example.com/post/1', 'submitted')").bind("id", submissionId).bind("app", applicationId)
				.bind("rec", recommender).then().block(Duration.ofSeconds(5));
		db.sql("INSERT INTO engagement_verification(id, submission_id, status, checks)"
				+ " VALUES (CAST(:id AS uuid), CAST(:sub AS uuid), 'inconclusive', '[]'::jsonb)")
				.bind("id", UUID.randomUUID().toString()).bind("sub", submissionId).then().block(Duration.ofSeconds(5));
		return submissionId;
	}

	/** identity 的 mail/outbox 会按 accountId 解析 email，owner 必须存在于共享 app_users。 */
	private void insertOwnerAccount(String owner) {
		db.sql("INSERT INTO app_users(id, email, password_hash, role, status)"
				+ " VALUES (CAST(:id AS uuid), :email, 'x-bcrypt-placeholder', 'user', 'active')").bind("id", owner)
				.bind("email", "owner-" + owner + "@cross-kafka.test").then().block(Duration.ofSeconds(5));
	}

	/**
	 * Java bootstrap 管理的共享基础表：identity Flyway 不建它们，手动补（幂等，镜像 IdentityItSupport）。
	 */
	private static void createSharedBootstrapTables() throws Exception {
		try (var c = java.sql.DriverManager.getConnection(MarketplaceItSupport.POSTGRES.getJdbcUrl(),
				MarketplaceItSupport.POSTGRES.getUsername(), MarketplaceItSupport.POSTGRES.getPassword());
				var s = c.createStatement()) {
			s.execute("CREATE TABLE IF NOT EXISTS app_users (id uuid PRIMARY KEY, email text NOT NULL UNIQUE,"
					+ " password_hash text NOT NULL, display_name text, role text NOT NULL DEFAULT 'user',"
					+ " status text NOT NULL DEFAULT 'active', created_at timestamptz DEFAULT now(),"
					+ " updated_at timestamptz DEFAULT now(), last_login_at timestamptz)");
			s.execute("CREATE TABLE IF NOT EXISTS session (sid varchar PRIMARY KEY, sess json NOT NULL,"
					+ " expire timestamp(6) NOT NULL)");
		}
	}

	private static Map<String, Object> identityProps() throws IOException {
		Map<String, Object> props = new LinkedHashMap<>();
		props.put("server.port", "0");
		props.put("management.server.port", "0");
		props.put("identity.datasource.from-database-url", "true");
		props.put("DATABASE_URL",
				"postgresql://" + MarketplaceItSupport.POSTGRES.getUsername() + ":"
						+ MarketplaceItSupport.POSTGRES.getPassword() + "@" + MarketplaceItSupport.POSTGRES.getHost()
						+ ":" + MarketplaceItSupport.POSTGRES.getMappedPort(5432) + "/"
						+ MarketplaceItSupport.POSTGRES.getDatabaseName());
		unpackedMigrationsDir = unpackIdentityMigrations();
		props.put("identity.flyway.locations", "filesystem:" + unpackedMigrationsDir);
		props.put("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers());
		props.put("identity.outbox.enabled", "false");
		props.put("identity.kyb.retention.enabled", "false");
		props.put("identity.session.secret", "test-secret-32-chars-minimum!!!");
		props.put("identity.external-delivery.challenge-secret", "test-sms-challenge-secret-at-least-32-characters");
		props.put("identity.notification-consumer.external.enabled", "true");
		props.put("identity.notification-consumer.external.marketplace-topic", MARKETPLACE_TOPIC);
		props.put("identity.notification-consumer.external.marketplace-group-id", GROUP);
		// identity 读到的是 marketplace 的 application.yml（classpath 两份同名文件、marketplace
		// resources 在前），
		// temporal starter 又在本测试 classpath 上——按该 yml 的 Temporal 目标连 gRPC 会启动即炸。
		// identity 生产不含 temporal，显式排除其全部自动配置。
		props.put("spring.autoconfigure.exclude",
				"io.temporal.spring.boot.autoconfigure.MetricsScopeAutoConfiguration,"
						+ "io.temporal.spring.boot.autoconfigure.OpenTracingAutoConfiguration,"
						+ "io.temporal.spring.boot.autoconfigure.TestServerAutoConfiguration,"
						+ "io.temporal.spring.boot.autoconfigure.ServiceStubsAutoConfiguration,"
						+ "io.temporal.spring.boot.autoconfigure.RootNamespaceAutoConfiguration,"
						+ "io.temporal.spring.boot.autoconfigure.NonRootNamespaceAutoConfiguration");
		props.putAll(TestAssertionHelper.keyringProperties("identity",
				List.of(new KeySpec("identity", "service", "grassland-finance"),
						new KeySpec("identity", "service", "grassland-intelligence")),
				List.of(new KeySpec("edge-bff", "user", "grassland-identity"),
						new KeySpec("trust", "service", "grassland-identity"),
						new KeySpec("marketplace", "service", "grassland-identity"),
						new KeySpec("intelligence", "service", "grassland-identity"))));
		return props;
	}

	/**
	 * {@link org.springframework.boot.builder.SpringApplicationBuilder} 的属性走
	 * defaultProperties（低优先级），转命令行参数保证覆盖 yml。
	 */
	private static String[] commandLineArgs(Map<String, Object> props) {
		return props.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
				.toArray(String[]::new);
	}

	/**
	 * identity 的迁移在 plain jar 内，解包到临时目录供 filesystem location 使用（checksum 与生产一致）。
	 */
	private static Path unpackIdentityMigrations() throws IOException {
		URL resource = IdentityServiceApplication.class.getClassLoader()
				.getResource("db/migration/V1__add_organization.sql");
		if (resource == null) {
			throw new IllegalStateException("identity migrations not on classpath");
		}
		String jarPath = java.net.URLDecoder.decode(
				resource.toString().replaceFirst("^jar:file:", "").replaceFirst("!.*$", ""), StandardCharsets.UTF_8);
		Path target = Files.createTempDirectory("identity-migrations");
		try (ZipFile jar = new ZipFile(jarPath)) {
			var entries = jar.stream()
					.filter(entry -> entry.getName().startsWith("db/migration/") && entry.getName().endsWith(".sql"))
					.toList();
			for (var entry : entries) {
				Path out = target.resolve(Path.of(entry.getName()).getFileName());
				try (var input = jar.getInputStream(entry)) {
					Files.copy(input, out);
				}
			}
		}
		return target;
	}
}
