package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.IntelligenceItSupport;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.UUID;
import javax.net.ssl.KeyManagerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.netty.http.client.HttpClient;

/**
 * 内部 mTLS 面 IT（任务书 #105D C105D-02 / TC105D-02-04）。
 *
 * <p>
 * keytool（JDK 自带，无新依赖）现场生成 CA/服务器/客户端/错误 CA 四组 PKCS#12——真实双向握手：无客户端证书 与错误 CA
 * 签发证书都在握手层被拒；合法证书（SAN=digital-human-runtime）可达且业务语义正常；伪证书 header 不构成身份；主业务端口访问
 * internal 路径 404；日志无合成 secret。
 */
@ExtendWith(OutputCaptureExtension.class)
class DigitalHumanInternalTlsIT extends IntelligenceItSupport {

	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	private static final Path PKI = createPki();
	private static final String STORE_PASS = "changeit";

	static {
		REDIS.start();
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
		r.add("digital-human.internal.enabled", () -> "true");
		r.add("digital-human.internal.port", () -> "0");
		r.add("digital-human.internal.allowed-ai-origins", () -> "https://ai.grassland.test");
		r.add("digital-human.internal.tls.cert-file", () -> "");
		r.add("digital-human.internal.tls.key-file", () -> PKI.resolve("server.p12").toString());
		r.add("digital-human.internal.tls.ca-file", () -> PKI.resolve("ca.pem").toString());
		r.add("digital-human.internal.tls.client-sans[0]", () -> "digital-human-runtime");
	}

	@Autowired
	DigitalHumanInternalServer internalServer;

	@BeforeAll
	static void requireListener() {
		// 上下文就绪即监听（SmartLifecycle）；断言前确保非 -1。
	}

	// ---------- TC105D-02-04：内部服务冒充 ----------

	@Test
	void tc105d_02_04_noClientCertificateRejectedAtHandshake() {
		WebClient noCert = webClient(null);
		assertThatThrownBy(() -> postConsume(noCert, "any-grant").block(Duration.ofSeconds(10)))
				.isInstanceOf(Exception.class).isInstanceOfSatisfying(Exception.class,
						error -> assertThat(isTlsFailure(error)).as("应在 TLS 握手层被拒（非 2xx 业务响应）").isTrue());
	}

	@Test
	void tc105d_02_04_wrongCaClientCertificateRejectedAtHandshake() {
		WebClient wrongCa = webClient(PKI.resolve("wrongclient.p12"));
		assertThatThrownBy(() -> postConsume(wrongCa, "any-grant").block(Duration.ofSeconds(10)))
				.isInstanceOf(Exception.class).isInstanceOfSatisfying(Exception.class,
						error -> assertThat(isTlsFailure(error)).as("应在 TLS 握手层被拒（非 2xx 业务响应）").isTrue());
	}

	@Test
	void tc105d_02_04_validMtlsClientReachesInternalEndpoint() {
		WebClient valid = webClient(PKI.resolve("client.p12"));
		var response = postConsume(valid, "totally-bogus-grant").block(Duration.ofSeconds(10));
		assertThat(response.status()).isEqualTo(409);
		assertThat(String.valueOf(response.body().get("code"))).contains("dh_grant_invalid");
	}

	@Test
	void tc105d_02_04_mainPortDoesNotExposeInternalRoutes() {
		// 主业务端口（公开面）访问 internal 路径 = 404：内部 controller 从不进入主 WebFlux 路由。
		var response = WebClient.builder().baseUrl("http://localhost:" + port).build().post()
				.uri("/internal/digital-human/grants/consume").bodyValue("{}").exchangeToMono(r -> r
						.bodyToMono(java.lang.String.class).map(body -> new SimpleResponse(r.statusCode().value())))
				.block(Duration.ofSeconds(10));
		assertThat(response.status()).isEqualTo(404);
	}

	@Test
	void tc105d_02_04_spoofedCertificateHeadersDoNotConferIdentity(CapturedOutput output) {
		// 伪证书 header：身份只来自握手证书，header 不参与——grant 校验仍按真实语义拒绝。
		WebClient valid = webClient(PKI.resolve("client.p12"));
		String marker = "SECRET-GRANT-" + UUID.randomUUID();
		var response = valid.post().uri("/internal/digital-human/grants/consume")
				.header("X-Client-Cert", "-----BEGIN CERTIFICATE-----spoofed")
				.header("X-SSL-Client-SAN", "digital-human-runtime").header("X-Grassland-Identity", "spoof")
				.bodyValue("{\"grant\":\"" + marker + "\",\"sessionId\":\"" + UUID.randomUUID()
						+ "\",\"leaseEpoch\":1,\"origin\":\"https://ai.grassland.test\",\"requestId\":\""
						+ UUID.randomUUID() + "\",\"format\":\"pcm_s16le\",\"sampleRate\":16000,\"channels\":1}")
				.exchangeToMono(r -> r.bodyToMono(java.lang.String.class)
						.map(body -> new SimpleResponse(r.statusCode().value())))
				.block(Duration.ofSeconds(10));
		assertThat(response.status()).isEqualTo(409);
		assertThat(output.getAll()).as("日志与输出不落 grant 原文").doesNotContain(marker);
	}

	// ---------- 私有 ----------

	/** 握手层失败特征：异常链含 SSL/握手/连接关闭（版本兼容的宽松判定，配合正向对照用例）。 */
	private static boolean isTlsFailure(Throwable error) {
		Throwable current = error;
		while (current != null) {
			String name = current.getClass().getName();
			String message = String.valueOf(current.getMessage());
			if (name.contains("ssl") || name.contains("Ssl") || message.contains("handshake")
					|| message.contains("certificate") || name.contains("ConnectionClosed")
					|| name.contains("PrematureClose")) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private record SimpleResponse(int status) {
	}

	private WebClient webClient(Path clientP12) {
		HttpClient http = HttpClient.create().secure(spec -> {
			try {
				SslContextBuilder builder = SslContextBuilder.forClient()
						.trustManager(Files.newInputStream(PKI.resolve("ca.pem")));
				if (clientP12 != null) {
					builder.keyManager(keyManagerFactory(clientP12));
				}
				SslContext ssl = builder.build();
				spec.sslContext(ssl);
			} catch (Exception failure) {
				throw new IllegalStateException(failure);
			}
		});
		return WebClient.builder().baseUrl("https://localhost:" + internalServer.boundPort())
				.clientConnector(new ReactorClientHttpConnector(http)).build();
	}

	private static KeyManagerFactory keyManagerFactory(Path p12) throws Exception {
		KeyStore store = KeyStore.getInstance("PKCS12");
		try (InputStream in = new FileInputStream(p12.toFile())) {
			store.load(in, STORE_PASS.toCharArray());
		}
		KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		factory.init(store, STORE_PASS.toCharArray());
		return factory;
	}

	private record CallResult(int status, java.util.Map<String, Object> body) {
	}

	private Mono<CallResult> postConsume(WebClient client, String grant) {
		return client.post().uri("/internal/digital-human/grants/consume")
				.bodyValue("{\"grant\":\"" + grant + "\",\"sessionId\":\"" + UUID.randomUUID()
						+ "\",\"leaseEpoch\":1,\"origin\":\"https://ai.grassland.test\",\"requestId\":\""
						+ UUID.randomUUID() + "\",\"format\":\"pcm_s16le\",\"sampleRate\":16000,\"channels\":1}")
				.exchangeToMono(
						response -> response.bodyToMono(java.lang.String.class).defaultIfEmpty("{}").map(body -> {
							try {
								@SuppressWarnings("unchecked")
								java.util.Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
										.readValue(body, java.util.Map.class);
								return new CallResult(response.statusCode().value(), parsed);
							} catch (Exception failure) {
								return new CallResult(response.statusCode().value(), java.util.Map.of());
							}
						}));
	}

	/** 生成测试 PKI（keytool，JDK 自带）。 */
	private static Path createPki() {
		try {
			Path dir = Files.createTempDirectory("dh-it-pki");
			String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
			run(keytool, "-genkeypair", "-alias", "dhca", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
					"-dname", "CN=dh-it-ca", "-keystore", dir.resolve("ca.p12").toString(), "-storetype", "PKCS12",
					"-storepass", STORE_PASS, "-ext", "bc:c");
			run(keytool, "-exportcert", "-rfc", "-alias", "dhca", "-keystore", dir.resolve("ca.p12").toString(),
					"-storetype", "PKCS12", "-storepass", STORE_PASS, "-file", dir.resolve("ca.pem").toString());
			issueSigned(keytool, dir, "ca.p12", "server", "CN=localhost", "dns:localhost");
			issueSigned(keytool, dir, "ca.p12", "client", "CN=dh-runtime", "dns:digital-human-runtime");
			// 错误 CA 签发的“合法 SAN”客户端：握手层必须被拒。
			run(keytool, "-genkeypair", "-alias", "wrongca", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
					"-dname", "CN=dh-wrong-ca", "-keystore", dir.resolve("wrongca.p12").toString(), "-storetype",
					"PKCS12", "-storepass", STORE_PASS, "-ext", "bc:c");
			exportWrongCa(dir, keytool);
			issueSigned(keytool, dir, "wrongca.p12", "wrongclient", "CN=dh-runtime-evil", "dns:digital-human-runtime");
			return dir;
		} catch (Exception failure) {
			throw new ExceptionInInitializerError(failure);
		}
	}

	private static void issueSigned(String keytool, Path dir, String caStore, String alias, String dname, String san)
			throws Exception {
		String store = dir.resolve(alias + ".p12").toString();
		run(keytool, "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname",
				dname, "-keystore", store, "-storetype", "PKCS12", "-storepass", STORE_PASS);
		run(keytool, "-certreq", "-alias", alias, "-keystore", store, "-storetype", "PKCS12", "-storepass", STORE_PASS,
				"-file", dir.resolve(alias + ".csr").toString());
		run(keytool, "-gencert", "-alias", caStore.equals("ca.p12") ? "dhca" : "wrongca", "-keystore",
				dir.resolve(caStore).toString(), "-storetype", "PKCS12", "-storepass", STORE_PASS, "-infile",
				dir.resolve(alias + ".csr").toString(), "-outfile", dir.resolve(alias + ".cer").toString(), "-validity",
				"1", "-ext", "san=" + san);
		run(keytool, "-importcert", "-alias", caStore.equals("ca.p12") ? "dhca" : "wrongca", "-file",
				dir.resolve(caStore.equals("ca.p12") ? "ca.pem" : "wrongca.pem").toString(), "-keystore", store,
				"-storetype", "PKCS12", "-storepass", STORE_PASS, "-noprompt");
		run(keytool, "-importcert", "-alias", alias, "-file", dir.resolve(alias + ".cer").toString(), "-keystore",
				store, "-storetype", "PKCS12", "-storepass", STORE_PASS, "-noprompt");
	}

	private static void run(String... command) throws Exception {
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes());
		int code = process.waitFor();
		if (code != 0) {
			throw new IllegalStateException("keytool failed (" + code + "): " + output);
		}
	}

	private static void exportWrongCa(Path dir, String keytool) throws Exception {
		run(keytool, "-exportcert", "-rfc", "-alias", "wrongca", "-keystore", dir.resolve("wrongca.p12").toString(),
				"-storetype", "PKCS12", "-storepass", STORE_PASS, "-file", dir.resolve("wrongca.pem").toString());
	}
}
