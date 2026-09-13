package com.grassland.intelligence.ai.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.humanize.HumanizeInjectionService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FrozenTextExecutionServiceTest {
	private final AiExecutionService executions = mock(AiExecutionService.class);
	private final TextCompletionClient client = mock(TextCompletionClient.class);
	private final PlatformConcurrencyLimiter limiter = mock(PlatformConcurrencyLimiter.class);
	private final HumanizeInjectionService humanize = mock(HumanizeInjectionService.class);
	private final ProviderResolution provider = mock(ProviderResolution.class);
	private final PlatformConcurrencyLimiter.Lease lease = mock(PlatformConcurrencyLimiter.Lease.class);
	private final MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/test"));
	private final UUID snapshot = UUID.randomUUID();
	private final List<ChatMessage> messages = List.of(ChatMessage.user("冻结任务"));
	private final AiExecutionService.ExecutionContext context = new AiExecutionService.ExecutionContext(
			UUID.randomUUID(), null, "account", "text", provider, null, UUID.randomUUID(), null,
			CreditFeature.CREATION_ASSISTANT, true, "fixture-key", "v1", 12, 4096);
	private final FrozenTextExecutionService service = new FrozenTextExecutionService(executions, client, limiter,
			humanize);

	@BeforeEach
	void prepare() {
		when(provider.provider()).thenReturn("qwen");
		when(provider.model()).thenReturn("frozen-model");
		when(provider.baseUrl()).thenReturn("http://localhost");
		when(provider.platformModelVersion()).thenReturn(7);
		when(humanize.injectForFeature(messages, CreditFeature.CREATION_ASSISTANT)).thenReturn(Mono.just(messages));
		when(executions.prepareExecution(eq(exchange), eq("text"), eq(CreditFeature.CREATION_ASSISTANT), anyInt(),
				eq(4096), eq(true), eq(snapshot)))
				.thenReturn(Mono.just(AiExecutionService.ExecutionResult.allowed(context)));
		when(limiter.acquire(provider)).thenReturn(Mono.just(lease));
		when(lease.release()).thenReturn(Mono.empty());
		when(executions.normalizeProviderUsage(eq(context), any())).thenAnswer(i -> i.getArgument(1));
		when(executions.settleSuccess(eq(context), anyInt(), anyInt(), eq(0), eq(0))).thenReturn(Mono.just(true));
		when(executions.handleFailure(eq(context), anyString())).thenReturn(Mono.just(true));
		when(executions.handleCancellation(context)).thenReturn(Mono.just(true));
		when(client.completeMessages(eq("qwen"), anyString(), anyString(), eq("frozen-model"), eq(messages), eq(4096),
				eq(false), any())).thenReturn(Mono.just(new TextCompletionResult("valid", 10, 5)));
	}

	@Test
	void oldSignaturePreservesDefaultsAndFrozenTrace() {
		var result = service.executeTraced(exchange, snapshot, messages, 4096, CreditFeature.CREATION_ASSISTANT,
				TextCompletionResult::content).block();
		assertThat(result.value()).isEqualTo("valid");
		assertThat(result.runId()).isEqualTo(context.runId());
		assertThat(result.platformModelVersion()).isEqualTo(7);
		verify(client).completeMessages(eq("qwen"), anyString(), anyString(), eq("frozen-model"), eq(messages),
				eq(4096), eq(false), isNull());
		verify(executions).settleSuccess(context, 10, 5, 0, 0);
	}

	@Test
	void verifiedCallerKeepsFrozenOwnershipAndDoesNotConsumeTheHttpAssertionAgain() {
		var caller = new com.grassland.intelligence.security.IntelligenceCallerResolver.Caller("account", "recommender",
				"session", "organization", "level1", "user", null, "user");
		when(executions.prepareAuthenticatedExecution(eq(caller), eq("text"), eq(CreditFeature.CREATION_ASSISTANT),
				anyInt(), eq(4096), eq(true), eq(snapshot)))
				.thenReturn(Mono.just(AiExecutionService.ExecutionResult.allowed(context)));
		var result = service.executeTraced(exchange, caller, snapshot, messages, 4096, CreditFeature.CREATION_ASSISTANT,
				Duration.ofSeconds(90), TextCompletionResult::content).block();
		assertThat(result.value()).isEqualTo("valid");
		assertThat(exchange.<UUID>getAttribute(FrozenTextExecutionService.RUN_ID_ATTRIBUTE)).isEqualTo(context.runId());
		verify(executions).prepareAuthenticatedExecution(eq(caller), eq("text"), eq(CreditFeature.CREATION_ASSISTANT),
				anyInt(), eq(4096), eq(true), eq(snapshot));
		verify(executions, never()).prepareExecution(eq(exchange), anyString(), any(), anyInt(), anyInt(), anyBoolean(),
				any());
		verify(executions).settleSuccess(context, 10, 5, 0, 0);
	}

	@Test
	void canvasTimeoutIsPassedInsideExistingExecutionAndValidationFailureCompensates() {
		StepVerifier.create(service.executeTraced(exchange, snapshot, messages, 4096, CreditFeature.CREATION_ASSISTANT,
				Duration.ofSeconds(90), result -> {
					throw new IllegalArgumentException("invalid plan");
				})).expectErrorMessage("invalid plan").verify();
		assertThat(exchange.<UUID>getAttribute(FrozenTextExecutionService.RUN_ID_ATTRIBUTE)).isEqualTo(context.runId());
		verify(client).completeMessages(eq("qwen"), anyString(), anyString(), eq("frozen-model"), eq(messages),
				eq(4096), eq(false), eq(Duration.ofSeconds(90)));
		verify(executions).handleFailure(context, "invalid plan");
		verify(executions, never()).settleSuccess(any(), anyInt(), anyInt(), anyInt(), anyInt());
		verify(lease).release();
	}

	@Test
	void modelDeadlineUsesTheFailureCompensationPath() {
		when(client.completeMessages(anyString(), anyString(), anyString(), anyString(), anyList(), anyInt(),
				anyBoolean(), any()))
				.thenAnswer(invocation -> Mono.delay(invocation.<Duration>getArgument(7)).then(Mono.error(
						new com.grassland.intelligence.security.IntelligenceException(504, "AI provider 调用超时"))));
		StepVerifier
				.withVirtualTime(() -> service.executeTraced(exchange, snapshot, messages, 4096,
						CreditFeature.CREATION_ASSISTANT, Duration.ofSeconds(90), TextCompletionResult::content))
				.expectSubscription().thenAwait(Duration.ofSeconds(89)).expectNoEvent(Duration.ofMillis(999))
				.thenAwait(Duration.ofMillis(1))
				.expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(
						com.grassland.intelligence.security.IntelligenceException.class,
						failure -> assertThat(failure.status()).isEqualTo(504)))
				.verify();
		verify(executions).handleFailure(context, "AI provider 调用超时");
		verify(lease).release();
		verify(executions, never()).handleCancellation(any());
		verify(executions, never()).settleSuccess(any(), anyInt(), anyInt(), anyInt(), anyInt());
	}

	@Test
	void userCancellationPreservesTheExistingCancellationPolicy() {
		when(client.completeMessages(anyString(), anyString(), anyString(), anyString(), anyList(), anyInt(),
				anyBoolean(), any())).thenReturn(Mono.never());
		StepVerifier
				.withVirtualTime(() -> service.executeTraced(exchange, snapshot, messages, 4096,
						CreditFeature.CREATION_ASSISTANT, Duration.ofSeconds(90), TextCompletionResult::content))
				.expectSubscription().thenAwait(Duration.ofSeconds(1)).thenCancel().verify();
		verify(executions).handleCancellation(context);
		verify(lease).release();
		verify(executions, never()).handleFailure(any(), anyString());
		verify(executions, never()).settleSuccess(any(), anyInt(), anyInt(), anyInt(), anyInt());
	}
}
