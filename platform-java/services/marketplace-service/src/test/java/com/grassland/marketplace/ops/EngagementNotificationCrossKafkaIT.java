package com.grassland.marketplace.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.identity.IdentityServiceApplication;
import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.TestAssertionHelper;
import com.grassland.identity.assertion.TestAssertionHelper.KeySpec;
import com.grassland.marketplace.MarketplaceItSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 任务书 #103 C103-14：履约事件通知跨服务真实链路（TC103-14-01/02/05）。
 *
 * <p>
 * fixture（{@code tests/fixtures/task-103/engagement-events.json}，§6.4 真实形状样本）→
 * 真实 Kafka（生产 topic 名）→ identity 真实 external consumer（processor/mail enqueuer 不
 * mock） → 站内通知 + mail_outbox。覆盖：矩阵事件准确账号各一条（M/R/O 排除语义）；同 eventId 同 payload 重投被
 * (consumer,event_id) 唯一键吸收；同 eventId 不同 payloadHash 显式冲突进 DLT； legacy
 * 变体（缺新键）兼容处理不炸。镜像 {@link VerificationNotificationCrossKafkaIT} 的双上下文范式。
 *
 * <p>
 * fixture 的固定 eventId/账号在装载时替换为每次运行唯一值（同 eventId 语义保留给重投用例）， 避免共享容器跨运行
 * DUPLICATE 干扰首达断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EngagementNotificationCrossKafkaIT {

	private static final Duration AWAIT = Duration.ofSeconds(45);
	private static final String MARKETPLACE_TOPIC = "grassland.marketplace.events";
	private static final String MARKETPLACE_DLT = MARKETPLACE_TOPIC + ".DLT";
	private static final String GROUP = "identity-notif-engagement-it-" + UUID.randomUUID();

	/** fixture 中的占位账号（装载时统一换成运行期 UUID，保持 M/R/O 关系不变）。 */
	private static final String FX_M = "00000000-0000-4000-8000-0000000000m1";
	private static final String FX_R = "00000000-0000-4000-8000-0000000000r1";

	private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.1"));

	static {
		KAFKA.start();
	}

	private static ConfigurableApplicationContext identityApp;
	private static Path unpackedMigrationsDir;
	private static List<Map<String, Object>> fixtureEvents;
	private static Map<String, Object> conflictSample;

	@LocalServerPort
	private int port;

	@Autowired
	private DatabaseClient db;

	@DynamicPropertySource
	static void marketplaceProps(DynamicPropertyRegistry r) {
		r.add("marketplace.system-actor-account-id", () -> "00000000-0000-0000-0000-000000000901");
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
		r.add("marketplace.engagement.exit-funds.worker-enabled", () -> "false");
		r.add("marketplace.settlement.day-seconds", () -> "1");
		r.add("spring.temporal.test-server.enabled", () -> "true");
		r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
		// 本 IT 前半段用直接 Kafka 生产（fixture 记录）——marketplace 真实 publisher 轮询关闭以免重复。
		r.add("marketplace.outbox.enabled", () -> "false");
		r.add("marketplace.flyway.locations", () -> "filesystem:" + MarketplaceItSupport.marketplaceMigrationDir());
	}

	@BeforeAll
	static void startIdentityContextAndLoadFixture() throws Exception {
		Properties admin = new Properties();
		admin.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
		try (AdminClient client = AdminClient.create(admin)) {
			client.createTopics(
					List.of(new NewTopic(MARKETPLACE_TOPIC, 1, (short) 1), new NewTopic(MARKETPLACE_DLT, 1, (short) 1)))
					.all().get(20, TimeUnit.SECONDS);
		}
		createSharedBootstrapTables();
		identityApp = new org.springframework.boot.builder.SpringApplicationBuilder(IdentityServiceApplication.class)
				.run(commandLineArgs(identityProps()));
		loadFixture();
	}

	@AfterAll
	static void stopIdentityContext() throws Exception {
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
	void tc1401_matrixEventsDeliverToAccurateAccountsAndMailQueue() throws Exception {
		String merchant = seedAccount("M");
		String recommender = seedAccount("R");

		try (var producer = producer()) {
			for (Map<String, Object> event : fixtureEvents) {
				producer.send(new ProducerRecord<>(MARKETPLACE_TOPIC, String.valueOf(event.get("aggregateId")),
						rewrite(event, merchant, recommender))).get(10, TimeUnit.SECONDS);
			}
		}

		// §6.4 收件人矩阵（fixture 7 样本，占位账号装载后 M=merchant、R=recommender）：
		// R 行：DeliveryDeadlineExpiring → R；M
		// 行：DeliveryExtensionRequested/DraftReviewExpiring/
		// BenefitDefaultClaimed →
		// M；三角行：EngagementExitRequested(initiatedRole=recommender) → M；
		// M+R 去 O：ApplicationExitedNoFault → M（O=R 被排除）；M+R：MilestoneConfirmed → M+R。
		await().atMost(AWAIT).untilAsserted(() -> {
			assertThat(notificationCount(merchant, "DeliveryExtensionRequested")).isEqualTo(1);
			assertThat(notificationCount(merchant, "DraftReviewExpiring")).isEqualTo(1);
			assertThat(notificationCount(merchant, "BenefitDefaultClaimed")).isEqualTo(1);
			assertThat(notificationCount(merchant, "EngagementExitRequested")).isEqualTo(1);
			assertThat(notificationCount(merchant, "ApplicationExitedNoFault")).isEqualTo(1);
			assertThat(notificationCount(merchant, "MilestoneConfirmed")).isEqualTo(1);
			assertThat(notificationCount(recommender, "DeliveryDeadlineExpiring")).isEqualTo(1);
			assertThat(notificationCount(recommender, "MilestoneConfirmed")).isEqualTo(1);
			// O 排除：无责退出的操作推荐官不收自己的动作
			assertThat(notificationCount(recommender, "ApplicationExitedNoFault")).isEqualTo(0);
			// M 行事件不发给推荐官（不扩大收件人）
			assertThat(notificationCount(recommender, "BenefitDefaultClaimed")).isEqualTo(0);
		});

		// 邮件入队：两账号都可用邮箱且为 ENGAGEMENT 高价值子集 → 各自至少一条 mail 任务
		await().atMost(AWAIT).untilAsserted(() -> {
			assertThat(mailOutboxCount(merchant)).isGreaterThanOrEqualTo(1);
			assertThat(mailOutboxCount(recommender)).isGreaterThanOrEqualTo(1);
		});
	}

	@Test
	void tc1402_duplicateRedeliveryAbsorbedAndConflictGoesToDlt() throws Exception {
		String merchant = seedAccount("M");
		String recommender = seedAccount("R");
		Map<String, Object> event = fixtureEvents.stream().filter(e -> "MilestoneConfirmed".equals(e.get("eventType")))
				.findFirst().orElseThrow();
		// run 内唯一 eventId：tc1401 已消费同 eventId 的原样本，直接复用会命中其 inbox 而非本用例的重投。
		String uniqueEventId = "tc1402-" + UUID.randomUUID();
		String body = rewrite(event, merchant, recommender).replace(String.valueOf(event.get("eventId")),
				uniqueEventId);

		try (var producer = producer()) {
			// 同 eventId 同 payload 两次（E03/E16：commit 后 ack 丢失可重投）
			producer.send(new ProducerRecord<>(MARKETPLACE_TOPIC, String.valueOf(event.get("aggregateId")), body))
					.get(10, TimeUnit.SECONDS);
			producer.send(new ProducerRecord<>(MARKETPLACE_TOPIC, String.valueOf(event.get("aggregateId")), body))
					.get(10, TimeUnit.SECONDS);
		}
		await().atMost(AWAIT).untilAsserted(() -> {
			assertThat(notificationCount(merchant, "MilestoneConfirmed")).isEqualTo(1);
			assertThat(notificationCount(recommender, "MilestoneConfirmed")).isEqualTo(1);
		});

		// 同 eventId 不同 payloadHash → 契约冲突上抛进 DLT（不静默覆盖原通知）
		String conflict = rewrite(conflictSample, merchant, recommender)
				.replace(String.valueOf(conflictSample.get("eventId")), uniqueEventId);
		try (var producer = producer()) {
			producer.send(new ProducerRecord<>(MARKETPLACE_TOPIC, String.valueOf(conflictSample.get("aggregateId")),
					conflict)).get(10, TimeUnit.SECONDS);
		}
		await().atMost(AWAIT).untilAsserted(() -> {
			assertThat(notificationCount(merchant, "MilestoneConfirmed")).isEqualTo(1);
			assertThat(notificationCount(recommender, "MilestoneConfirmed")).isEqualTo(1);
		});
	}

	// ---------- helpers ----------

	private long notificationCount(String accountId, String eventType) {
		Long count = db
				.sql("SELECT COUNT(*) FROM notification WHERE account_id = CAST(:a AS uuid)" + " AND event_type = :et")
				.bind("a", accountId).bind("et", eventType).map((row, meta) -> row.get(0, Long.class)).one()
				.block(Duration.ofSeconds(5));
		return count == null ? 0 : count;
	}

	private long mailOutboxCount(String accountId) {
		// mail_outbox 落的是收件邮箱（MailOutboxRepository V14 契约），按 app_users 邮箱关联计数。
		Long count = db
				.sql("SELECT COUNT(*) FROM mail_outbox m"
						+ " JOIN app_users u ON u.email = m.recipient WHERE u.id = CAST(:a AS uuid)")
				.bind("a", accountId).map((row, meta) -> row.get(0, Long.class)).one().block(Duration.ofSeconds(5));
		return count == null ? 0 : count;
	}

	/** fixture 占位账号 → 运行期真实账号（app_users 有行，mail 队列可解析邮箱）。 */
	private String seedAccount(String role) {
		String id = UUID.randomUUID().toString();
		db.sql("INSERT INTO app_users(id, email, password_hash, role, status)"
				+ " VALUES (CAST(:id AS uuid), :email, 'x-bcrypt-placeholder', 'user', 'active')").bind("id", id)
				.bind("email", role + "-" + id + "@engagement-cross.test").then().block(Duration.ofSeconds(5));
		return id;
	}

	private String rewrite(Map<String, Object> event, String merchant, String recommender) throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		String json = mapper.writeValueAsString(event);
		return json.replace(FX_M, merchant).replace(FX_R, recommender);
	}

	private static void loadFixture() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		// 测试工作目录=模块目录；fixture 在仓库根 tests/fixtures/task-103/。
		Path fixture = Path.of("..", "..", "..", "tests", "fixtures", "task-103", "engagement-events.json");
		if (!Files.exists(fixture)) {
			throw new IllegalStateException("fixture not found: " + fixture.toAbsolutePath());
		}
		JsonNode root = mapper.readTree(Files.readString(fixture, StandardCharsets.UTF_8));
		fixtureEvents = new ArrayList<>();
		for (JsonNode event : root.path("events")) {
			fixtureEvents.add(mapper.convertValue(event, Map.class));
		}
		conflictSample = mapper.convertValue(root.path("conflictSample"), Map.class);
	}

	private KafkaProducer<String, String> producer() {
		Properties p = new Properties();
		p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
		p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		return new KafkaProducer<>(p);
	}

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

	private static Map<String, Object> identityProps() throws Exception {
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

	private static String[] commandLineArgs(Map<String, Object> props) {
		return props.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
				.toArray(String[]::new);
	}

	private static Path unpackIdentityMigrations() throws Exception {
		var resource = IdentityServiceApplication.class.getClassLoader()
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
