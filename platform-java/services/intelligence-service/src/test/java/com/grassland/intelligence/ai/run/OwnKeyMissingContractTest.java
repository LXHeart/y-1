package com.grassland.intelligence.ai.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.byok.ByokRoutingService;
import com.grassland.intelligence.articleimage.ArticleImageService;
import com.grassland.intelligence.articleimage.ImageGenerationConfig;
import com.grassland.intelligence.articleimage.IndependentImageGenerationService;
import com.grassland.intelligence.humanize.HumanizeInjectionService;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class OwnKeyMissingContractTest {

	@Test
	void independentTextExecutionReturnsActionable422WithoutUpstreamCall() {
		var executions = mock(AiExecutionService.class);
		var textClient = mock(TextCompletionClient.class);
		var humanize = mock(HumanizeInjectionService.class);
		var limiter = mock(PlatformConcurrencyLimiter.class);
		var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/article/draft"));
		var messages = List.of(ChatMessage.user("测试文本"));
		when(humanize.injectForFeature(messages, null)).thenReturn(Mono.just(messages));
		when(executions.prepareExecution(eq(exchange), eq("text"), isNull(), anyInt(), eq(128), eq(true), isNull()))
				.thenReturn(Mono.just(AiExecutionService.ExecutionResult.denied("own_key_missing")));
		var service = new FrozenTextExecutionService(executions, textClient, limiter, humanize);

		StepVerifier.create(service.executeIndependent(exchange, messages, 128, null, TextCompletionResult::content))
				.expectErrorSatisfies(OwnKeyMissingContractTest::assertOwnKeyMissing).verify();
		verifyNoInteractions(textClient, limiter);
	}

	@Test
	void routedTextReturnsActionable422WithoutDecryptingOrCallingUpstream() {
		var routing = mock(ByokRoutingService.class);
		var decryptor = mock(ProviderKeyDecryptor.class);
		var textClient = mock(TextCompletionClient.class);
		when(routing.resolveProvider(isNull(), eq("account"), eq("text"), eq(true)))
				.thenReturn(Mono.just(ByokRoutingService.ProviderResolution.denied("own_key_missing")));
		var service = new RoutedTextCompletionService(mock(IntelligenceCallerResolver.class), routing, decryptor,
				textClient);

		StepVerifier
				.create(service.completeFor("account", null, List.of(ChatMessage.user("测试文本")), 128,
						Duration.ofSeconds(1), "生成失败"))
				.expectErrorSatisfies(OwnKeyMissingContractTest::assertOwnKeyMissing).verify();
		verifyNoInteractions(decryptor, textClient);
	}

	@Test
	void independentImageReturnsActionable422BeforeCreatingRun() {
		var routing = mock(ByokRoutingService.class);
		var images = mock(ArticleImageService.class);
		var executions = mock(AiExecutionService.class);
		when(routing.resolveProvider(isNull(), eq("account"), eq("image_generation"), eq(true)))
				.thenReturn(Mono.just(ByokRoutingService.ProviderResolution.denied("own_key_missing")));
		var service = new IndependentImageGenerationService(images, routing, mock(ImageGenerationConfig.class),
				executions);

		StepVerifier.create(service.generate(null, "account", null))
				.expectErrorSatisfies(OwnKeyMissingContractTest::assertOwnKeyMissing).verify();
		verifyNoInteractions(images, executions);
	}

	private static void assertOwnKeyMissing(Throwable error) {
		assertThat(error).isInstanceOf(IntelligenceException.class);
		assertThat(((IntelligenceException) error).status()).isEqualTo(422);
		assertThat(error.getMessage()).contains("未配置自有模型密钥", "AI 与治理", "切回平台统一模型");
	}
}
