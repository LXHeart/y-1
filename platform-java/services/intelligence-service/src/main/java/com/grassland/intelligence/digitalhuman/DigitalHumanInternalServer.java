package com.grassland.intelligence.digitalhuman;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * 独立 9143 mTLS 内部监听（任务书 #105D C105D-02 / 共享契约 K07.2、K13.2）。
 *
 * <p>
 * 有限 RouterFunction + Reactor Netty 独立 server，不复用主 WebFlux 路由——主业务端口永不挂
 * internal 映射（公网访问 internal 路径 = 404）。强制双向 TLS：客户端证书必须由配置 CA 签发且 SAN 命中
 * allowlist（默认 digital-human-runtime）；不信任任何转发证书 header。DH 关闭（默认）不启动监听。
 */
@Component
@EnableConfigurationProperties(DigitalHumanInternalProperties.class)
public class DigitalHumanInternalServer implements SmartLifecycle {

	private static final Logger logger = LoggerFactory.getLogger(DigitalHumanInternalServer.class);

	private final DigitalHumanInternalController controller;
	private final DigitalHumanInternalProperties properties;
	private volatile DisposableServer server;
	private volatile boolean running;

	public DigitalHumanInternalServer(DigitalHumanInternalController controller,
			DigitalHumanInternalProperties properties) {
		this.controller = controller;
		this.properties = properties;
	}

	@Override
	public void start() {
		if (!properties.enabled() || running) {
			return;
		}
		try {
			SslContext ssl = buildSslContext();
			RouterFunction<ServerResponse> routes = routes();
			HttpServer http = HttpServer.create().port(properties.port()).secure(spec -> spec.sslContext(ssl))
					.handle(new org.springframework.http.server.reactive.ReactorHttpHandlerAdapter(
							RouterFunctions.toHttpHandler(routes)));
			server = http.bindNow();
			running = true;
			logger.info("digital-human internal mTLS listener started on port {} (SAN allowlist: {})", boundPort(),
					properties.tls().clientSans());
		} catch (Exception failure) {
			// 启动失败即 fail-fast：内部面不可用比带病运行安全（K10：重启不能把开关默认成 true）。
			throw new IllegalStateException("数字人内部 mTLS 监听启动失败", failure);
		}
	}

	@Override
	public void stop() {
		if (server != null) {
			server.disposeNow();
			server = null;
		}
		running = false;
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	/** 实际绑定端口（port=0 时为临时端口，IT/部署探测用）。 */
	public int boundPort() {
		return server == null ? -1 : ((java.net.InetSocketAddress) server.address()).getPort();
	}

	RouterFunction<ServerResponse> routes() {
		return RouterFunctions.route()
				.POST("/internal/digital-human/grants/consume",
						request -> DigitalHumanInternalController.handled(controller.consumeGrant(request)))
				.POST("/internal/digital-human/events",
						request -> DigitalHumanInternalController.handled(controller.reportEvent(request)))
				.POST("/internal/digital-human/invocations",
						request -> DigitalHumanInternalController.handled(controller.createInvocation(request)))
				.POST("/internal/digital-human/invocations/{id}/execute",
						request -> DigitalHumanInternalController.handled(controller.executeInvocation(request)))
				.POST("/internal/digital-human/render-invocations/{id}/control",
						request -> DigitalHumanInternalController.handled(controller.renderControl(request)))
				.POST("/internal/digital-human/render-invocations/{id}/connection-grants",
						request -> DigitalHumanInternalController.handled(controller.renderConnectionGrant(request)))
				// #105F C105F-01 INTERNAL11：runtime→Java 媒体产物回执（avatar 受理；recording 随 F02）。
				.POST("/internal/digital-human/artifacts",
						request -> DigitalHumanInternalController.handled(controller.reportArtifact(request)))
				// 未声明路径一律 404：内部面不提供发现性。
				.GET("/internal/digital-human/health",
						request -> ServerResponse.ok().bodyValue(java.util.Map.of("ok", true)))
				.build();
	}

	private SslContext buildSslContext() throws Exception {
		DigitalHumanInternalProperties.Tls tls = properties.tls();
		KeyManagerFactory keyManagers;
		// 支持 PEM（cert+key 对，生产默认）与 PKCS#12 单文件（keyFile 指 p12、certFile 留空；IT 用）。
		if (tls.keyFile() != null && tls.keyFile().endsWith(".p12")
				&& (tls.certFile() == null || tls.certFile().isBlank())) {
			KeyStore store = KeyStore.getInstance("PKCS12");
			try (InputStream in = new FileInputStream(tls.keyFile())) {
				store.load(in, "changeit".toCharArray());
			}
			keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			keyManagers.init(store, "changeit".toCharArray());
		} else {
			keyManagers = null;
		}
		TrustManager[] trustManagers = trustManagers(tls);
		SslContextBuilder builder = keyManagers != null
				? SslContextBuilder.forServer(keyManagers)
				: SslContextBuilder.forServer(Path.of(tls.certFile()).toFile(), Path.of(tls.keyFile()).toFile());
		return builder.trustManager(trustManagers[0]).clientAuth(ClientAuth.REQUIRE).build();
	}

	/** 默认 CA 校验 + 客户端 SAN allowlist（K13.2：身份只认握手证书）。 */
	private TrustManager[] trustManagers(DigitalHumanInternalProperties.Tls tls) throws Exception {
		KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
		trusted.load(null, null);
		CertificateFactory factory = CertificateFactory.getInstance("X.509");
		try (InputStream in = new FileInputStream(tls.caFile())) {
			Collection<? extends Certificate> cas = factory.generateCertificates(in);
			int index = 0;
			for (Certificate ca : cas) {
				trusted.setCertificateEntry("dh-ca-" + index++, ca);
			}
		}
		TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		tmf.init(trusted);
		Set<String> allowlist = Set.copyOf(tls.clientSans());
		for (TrustManager manager : tmf.getTrustManagers()) {
			if (manager instanceof X509ExtendedTrustManager extended) {
				return new TrustManager[]{new SanCheckingTrustManager(extended, allowlist)};
			}
		}
		throw new IllegalStateException("JVM 未提供 X509ExtendedTrustManager");
	}

	/** SAN allowlist 包装：CA 信任之上强制客户端证书 SAN（DNS/URI）命中 allowlist。 */
	static final class SanCheckingTrustManager extends X509ExtendedTrustManager {

		private final X509ExtendedTrustManager delegate;
		private final Set<String> allowlist;

		SanCheckingTrustManager(X509ExtendedTrustManager delegate, Set<String> allowlist) {
			this.delegate = delegate;
			this.allowlist = allowlist;
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			delegate.checkClientTrusted(chain, authType);
			verifySan(chain);
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
				throws CertificateException {
			delegate.checkClientTrusted(chain, authType, socket);
			verifySan(chain);
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
				throws CertificateException {
			delegate.checkClientTrusted(chain, authType, engine);
			verifySan(chain);
		}

		private void verifySan(X509Certificate[] chain) throws CertificateException {
			X509Certificate client = chain[0];
			Collection<List<?>> sans = client.getSubjectAlternativeNames();
			boolean matched = false;
			if (sans != null) {
				for (List<?> san : sans) {
					// type 2=dNSName、7=URI（RFC 5280）。
					int type = (Integer) san.get(0);
					String value = String.valueOf(san.get(1));
					if ((type == 2 || type == 7) && allowlist.contains(value)) {
						matched = true;
						break;
					}
				}
			}
			if (!matched) {
				throw new CertificateException("client certificate SAN not in allowlist");
			}
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			delegate.checkServerTrusted(chain, authType);
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
				throws CertificateException {
			delegate.checkServerTrusted(chain, authType, socket);
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
				throws CertificateException {
			delegate.checkServerTrusted(chain, authType, engine);
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return delegate.getAcceptedIssuers();
		}
	}
}
