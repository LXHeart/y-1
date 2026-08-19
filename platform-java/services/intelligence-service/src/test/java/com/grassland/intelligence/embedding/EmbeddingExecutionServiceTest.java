package com.grassland.intelligence.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.ai.PlatformModelConfig;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.ai.run.AiExecutionService.ExecutionContext;
import com.grassland.intelligence.ai.run.AiExecutionService.ExecutionResult;
import com.grassland.intelligence.ai.run.PlatformConcurrencyLimiter;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 任务书 #33：Embedding 执行服务——retrieval 能力 Run、0 积分、Provider 校验与失败闭环。 */
class EmbeddingExecutionServiceTest {

    private AiExecutionService executions;
    private PlatformConcurrencyLimiter concurrency;
    private EmbeddingProviderRegistry providers;
    private EmbeddingExecutionService service;

    @BeforeEach
    void setUp() {
        executions = mock(AiExecutionService.class);
        concurrency = mock(PlatformConcurrencyLimiter.class);
        providers = mock(EmbeddingProviderRegistry.class);
        service = new EmbeddingExecutionService(executions, concurrency, providers);
        when(executions.settleSuccess(any(), any(), any(), anyInt(), anyInt())).thenReturn(Mono.just(true));
        when(executions.handleFailure(any(), anyString())).thenReturn(Mono.just(true));
        PlatformConcurrencyLimiter.Lease lease = mock(PlatformConcurrencyLimiter.Lease.class);
        when(lease.release()).thenReturn(Mono.empty());
        when(concurrency.acquire(any())).thenReturn(Mono.just(lease));
    }

    private static ExecutionContext context() {
        ProviderResolution provider = ProviderResolution.platform(
                UUID.randomUUID(), "sandbox", "https://sandbox.invalid", "sandbox-embedding-v1", 1, 4);
        return new ExecutionContext(
                UUID.randomUUID(), null, "acct-1", "retrieval", provider,
                null, UUID.randomUUID(), null, null, false, null, "v1", 0, 0);
    }

    private void allow(ExecutionContext ctx) {
        when(executions.prepareExecution(
                anyString(), any(), eq("retrieval"), eq(CreditFeature.AI_RUN_EMBEDDING),
                anyInt(), eq(0), eq(true)))
                .thenReturn(Mono.just(ExecutionResult.allowed(ctx)));
    }

    @Test
    void indexingUsesDirectPreparationAndReturnsVectorAndMetadata() {
        ExecutionContext ctx = context();
        allow(ctx);
        when(providers.require("sandbox")).thenReturn(new SandboxEmbeddingProvider());

        EmbeddingExecutionService.EmbeddingOutcome outcome =
                service.embedForIndexing("acct-1", null, "开业 门店 咖啡").block(Duration.ofSeconds(5));

        assertThat(outcome).isNotNull();
        assertThat(outcome.vector()).hasSize(256);
        assertThat(outcome.provider().model()).isEqualTo("sandbox-embedding-v1");
        assertThat(outcome.algorithmVersion()).isNotBlank();
        assertThat(outcome.runId()).isEqualTo(ctx.runId());
        verify(executions).settleSuccess(ctx, outcome.inputTokens(), 0, 0, 0);
    }

    @Test
    void queryUsesExchangePreparation() {
        ExecutionContext ctx = context();
        when(executions.prepareExecution(
                any(ServerWebExchange.class), eq("retrieval"), eq(CreditFeature.AI_RUN_EMBEDDING),
                anyInt(), eq(0), eq(true)))
                .thenReturn(Mono.just(ExecutionResult.allowed(ctx)));
        when(providers.require("sandbox")).thenReturn(new SandboxEmbeddingProvider());
        ServerWebExchange exchange = mock(ServerWebExchange.class);

        EmbeddingExecutionService.EmbeddingOutcome outcome =
                service.embedQuery(exchange, "门店 海报").block(Duration.ofSeconds(5));

        assertThat(outcome).isNotNull();
        assertThat(outcome.vector()).hasSize(256);
        verify(executions).settleSuccess(ctx, outcome.inputTokens(), 0, 0, 0);
    }

    @Test
    void deniedPreparationFailsWithStableCodeAndNeverSettles() {
        when(executions.prepareExecution(
                anyString(), any(), eq("retrieval"), eq(CreditFeature.AI_RUN_EMBEDDING),
                anyInt(), eq(0), eq(true)))
                .thenReturn(Mono.just(ExecutionResult.denied("no_platform_model")));

        assertThatThrownBy(() -> service.embedForIndexing("acct-1", null, "门店").block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(IntelligenceException.class, error -> {
                    assertThat(error.code()).isEqualTo("no_platform_model");
                    assertThat(error.status()).isEqualTo(503);
                });
        verify(executions, never()).settleSuccess(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void unsupportedProviderFailsTheRunWithCodedError() {
        ExecutionContext ctx = context();
        allow(ctx);
        when(providers.require("sandbox")).thenThrow(
                new IntelligenceException(503, "unsupported_provider", "暂不支持该Embedding模型供应商"));

        assertThatThrownBy(() -> service.embedForIndexing("acct-1", null, "门店").block(Duration.ofSeconds(5)))
                .isInstanceOf(IntelligenceException.class)
                .hasMessageContaining("Embedding");
        verify(executions).handleFailure(eq(ctx), anyString());
        verify(executions, never()).settleSuccess(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void invalidVectorFromProviderFailsTheRun() {
        ExecutionContext ctx = context();
        allow(ctx);
        EmbeddingProvider broken = new EmbeddingProvider() {
            @Override public String provider() { return "sandbox"; }
            @Override public String algorithmVersion() { return "sandbox-hash-v1"; }
            @Override public int dimensions() { return 256; }
            @Override public Mono<Result> embed(String normalizedText) {
                List<Double> shortVector = Stream.generate(() -> 0.1).limit(100).collect(Collectors.toList());
                return Mono.just(new Result(shortVector, 1, true));
            }
        };
        when(providers.require("sandbox")).thenReturn(broken);

        assertThatThrownBy(() -> service.embedForIndexing("acct-1", null, "门店").block(Duration.ofSeconds(5)))
                .isInstanceOf(IntelligenceException.class);
        verify(executions).handleFailure(eq(ctx), anyString());
    }

    @Test
    void providerErrorFailsTheRun() {
        ExecutionContext ctx = context();
        allow(ctx);
        EmbeddingProvider failing = new EmbeddingProvider() {
            @Override public String provider() { return "sandbox"; }
            @Override public String algorithmVersion() { return "sandbox-hash-v1"; }
            @Override public int dimensions() { return 256; }
            @Override public Mono<Result> embed(String normalizedText) {
                return Mono.error(new IllegalStateException("provider down"));
            }
        };
        when(providers.require("sandbox")).thenReturn(failing);

        assertThatThrownBy(() -> service.embedForIndexing("acct-1", null, "门店").block(Duration.ofSeconds(5)))
                .isInstanceOf(IntelligenceException.class);
        verify(executions).handleFailure(eq(ctx), anyString());
        verify(executions, never()).settleSuccess(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void realProviderReceivesRuntimeRoutingAndByokUsageCannotBeUnderreported() {
        ProviderResolution resolution = ProviderResolution.byok(
                "openai-compatible", "https://api.openai.com/v1", "embed-v1", "ciphertext", "v1");
        ExecutionContext ctx = new ExecutionContext(
                UUID.randomUUID(), null, "acct-1", "retrieval", resolution,
                null, UUID.randomUUID(), null, null, false, "decrypted-secret-key-1234", "v1", 8, 0);
        allow(ctx);
        EmbeddingProvider real = mock(EmbeddingProvider.class);
        when(real.dimensions()).thenReturn(3);
        when(real.algorithmVersion(any(EmbeddingProvider.Command.class))).thenReturn("real-v1");
        when(real.embed(any(EmbeddingProvider.Command.class)))
                .thenReturn(Mono.just(new EmbeddingProvider.Result(List.of(0.1, 0.2, 0.3), 2, false)));
        when(providers.require("openai-compatible")).thenReturn(real);
        EmbeddingProviderProperties properties = new EmbeddingProviderProperties(
                "openai-compatible", "https://api.openai.com/v1", "platform-secret-key-1234", "embed-v1",
                "/embeddings", Duration.ofSeconds(30), 65_536, 3, false, 1);
        PlatformModelConfig platformDefaults = mock(PlatformModelConfig.class);
        service = new EmbeddingExecutionService(executions, concurrency, providers, properties, platformDefaults);

        EmbeddingExecutionService.EmbeddingOutcome outcome =
                service.embedForIndexing("acct-1", null, "coffee shop poster").block(Duration.ofSeconds(5));

        ArgumentCaptor<EmbeddingProvider.Command> command = ArgumentCaptor.forClass(EmbeddingProvider.Command.class);
        verify(real).embed(command.capture());
        assertThat(command.getValue().invocation().bearer()).isEqualTo("decrypted-secret-key-1234");
        assertThat(command.getValue().invocation().byok()).isTrue();
        assertThat(outcome.inputTokens()).isEqualTo(8);
        assertThat(outcome.algorithmVersion()).isEqualTo("real-v1");
        assertThat(outcome.sandbox()).isFalse();
        verify(executions).settleSuccess(ctx, 8, 0, 0, 0);
    }
}
