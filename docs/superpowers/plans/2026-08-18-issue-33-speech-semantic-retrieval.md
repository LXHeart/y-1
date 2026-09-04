# #33 语音识别与 Embedding/语义检索实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 以可替换的 Sandbox Provider 交付语音转写和素材语义检索完整闭环，并复用现有媒体、模型控制面、AI Run、预算、权限与素材推荐基础设施。

**Architecture:** `intelligence-service` 新增 `voice` 与 `retrieval` 两个 Provider 端口。语音链路复用媒体三步上传并同步完成一次持久化转写；Embedding 链路以数据库 claim worker 异步维护版本化素材向量，推荐请求先构造权威可见候选，再在 Java 中做有界余弦排序并可降级到既有规则。

**Tech Stack:** Java 25、Spring Boot WebFlux、R2DBC、PostgreSQL/Flyway、Testcontainers、S3/MinIO ObjectStorageAdapter、Vue 3、TypeScript、Vitest/happy-dom、Gradle、Vite。

**Spec:** `docs/superpowers/specs/2026-08-18-speech-embedding-design.md`

---

## File Map

### Shared AI foundation

- Modify `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/ai/controlplane/PlatformProviderPolicy.java`: admit the built-in Sandbox origin without weakening Qwen URL policy.
- Modify `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/ai/controlplane/PlatformModelConfigSeeder.java`: seed missing `voice` and `retrieval` Sandbox models independently.
- Modify `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/ai/byok/ByokRoutingService.java`: preserve BYOK key version and expose a stable model-version key.
- Modify `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/creationcontext/FrozenAiConfigResolver.java`: pass the frozen BYOK key version into `ProviderResolution`.
- Modify `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/ai/run/AiExecutionService.java`: add a caller-independent overload for persistent workers.
- Modify `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/ai/run/PriceTableService.java`: register zero-cost Sandbox models.
- Modify `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/security/IntelligenceException.java` and `IntelligenceErrorHandler.java`: add optional stable error codes while preserving the existing `{success:false,error}` envelope.

### Speech capability

- Create `platform-java/services/intelligence-service/src/main/resources/db/migration/V31__speech_transcription.sql`: persistent transcription records.
- Modify `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/media/MediaPurpose.java` and `MediaController.java`: `speech_audio` tickets, MIME and size policy.
- Create `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/speech/SpeechAudioPolicy.java`: file-signature validation.
- Create `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/speech/AudioDurationProbe.java`: bounded `ffprobe` duration extraction.
- Create `SpeechRecognitionProvider.java`, `SandboxSpeechRecognitionProvider.java`, and `SpeechProviderRegistry.java` in the same package: Provider boundary and local implementation.
- Create `SpeechTranscription.java`, `SpeechTranscriptionRepository.java`, `SpeechTranscriptionService.java`, and `SpeechTranscriptionController.java`: persistence and authenticated API.

### Embedding and semantic retrieval

- Create `platform-java/services/intelligence-service/src/main/resources/db/migration/V32__content_asset_embedding.sql`: versioned vectors and reliable claim state.
- Create `EmbeddingProvider.java`, `SandboxEmbeddingProvider.java`, `EmbeddingProviderRegistry.java`, `EmbeddingTextNormalizer.java`, `CosineSimilarity.java`, and `SemanticRanker.java` under `com.grassland.intelligence.embedding`: deterministic vectors and ranking math.
- Create `ContentAssetEmbedding.java`, `ContentAssetEmbeddingRepository.java`, `EmbeddingIndexProperties.java`, `EmbeddingExecutionService.java`, and `EmbeddingIndexWorker.java`: indexing state machine, backfill and AI Run execution.
- Modify `ContentAssetController.java`, `ContentAssetAdminController.java`, `ContentAssetRecommendationService.java`, `ContentAssetRecommender.java`, and `ContentAssetRepository.java`: enqueue mutations and consume semantic scores after authorization.

### Edge, frontend and docs

- Modify `platform-java/services/edge-bff/src/main/resources/application.yml`, `docker-compose.yml`, and Edge tests: method-scoped `/api/speech` route with a dedicated flag.
- Modify `src/types/grassland/media.ts` and `src/composables/useGrasslandGovernance.ts`: speech and semantic wire contracts.
- Create `src/components/SpeechTranscriptionPanel.vue`; modify `src/views/ai-center/AiCreationCenter.vue`: speech workspace.
- Modify `src/components/MediaLibraryPanel.vue`: natural-language query, semantic scores and fallback state.
- Update `CLAUDE.md`, `docs/架构/草场旧API兼容契约矩阵.md`, `docs/草场开发进度与续接指南.md`, `项目速览.md`, and the design spec after verification.

## Global Constraints

- Preserve the user's existing uncommitted `项目速览.md` change. Read and merge with it in Task 13; never restore or overwrite it.
- Execute feature work on `codex/issue-33-speech-semantic-retrieval`; do not commit directly on `main`.
- Run Gradle from `platform-java/` with JDK 25: `JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew ...`.
- Every blocking ObjectStorage or process call runs on `Schedulers.boundedElastic()`; never block the WebFlux event loop.
- Sandbox Provider base URL is exactly `https://sandbox.invalid`. No network request may be made to that host.
- Sandbox speech and embedding cost 0 cents and pass `CreditFeature=null`, so they create `ai_run` and budget reservations without consuming user credits.
- Unsupported configured Providers fail with `unsupported_provider`; fallback occurs only when routing explicitly authorizes it.
- Semantic authorization precedes vector lookup. Never query all vectors and filter results afterward.
- No API or log emits audio bytes, full transcript text, raw query text, object keys, embeddings, content hashes, BYOK plaintext or upstream bodies.
- Each task ends with a focused commit. Use the commit messages shown below.

---

### Task 1: Register safe zero-cost Sandbox capabilities

**Files:**
- Modify: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/ai/controlplane/PlatformProviderPolicy.java`
- Modify: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/ai/controlplane/PlatformModelConfigSeeder.java`
- Modify: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/ai/run/PriceTableService.java`
- Test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/ai/controlplane/PlatformProviderPolicyTest.java`
- Create test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/ai/controlplane/PlatformModelConfigSeederTest.java`
- Create test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/ai/run/PriceTableServiceTest.java`

- [ ] **Step 1: Write failing policy, seeder and price tests**

```java
@Test
void sandboxOnlyAcceptsTheBuiltInNonRoutableOrigin() {
    assertThat(policy.validate("sandbox", "https://sandbox.invalid").toString())
            .isEqualTo("https://sandbox.invalid");
    assertThatThrownBy(() -> policy.validate("sandbox", "https://example.com"))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test
void seedsOnlyMissingVoiceAndRetrievalCapabilities() {
    seeder.run(new DefaultApplicationArguments(new String[0]));
    verify(repository).create(argThat(model -> model.capability().equals("voice")
            && model.provider().equals("sandbox")
            && model.model().equals("sandbox-speech-v1")), eq("system"));
    verify(repository).create(argThat(model -> model.capability().equals("retrieval")
            && model.provider().equals("sandbox")
            && model.model().equals("sandbox-embedding-v1")), eq("system"));
}

@Test
void sandboxModelsArePricedAtZero() {
    assertThat(prices.calculateCost("sandbox-speech-v1", 0, 0, 0, 0)).isZero();
    assertThat(prices.calculateCost("sandbox-embedding-v1", 400, 0, 0, 0)).isZero();
}
```

- [ ] **Step 2: Run the focused tests and confirm failure**

Run:

```bash
cd platform-java
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :services:intelligence-service:test --tests '*PlatformProviderPolicyTest' --tests '*PlatformModelConfigSeederTest' --tests '*PriceTableServiceTest'
```

Expected: failures because `sandbox` is rejected, capabilities are not seeded, and prices are unknown.

- [ ] **Step 3: Implement the safe Sandbox policy and independent seeding**

Use these exact constants and keep the existing Qwen branch unchanged:

```java
private static final String QWEN = "qwen";
private static final String SANDBOX = "sandbox";
private static final String SANDBOX_BASE_URL = "https://sandbox.invalid";

public URI validate(String provider, String baseUrl) {
    String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    if (SANDBOX.equals(normalized)) {
        URI uri = ProviderUrlGuard.validate(baseUrl);
        if (!SANDBOX_BASE_URL.equals(uri.toString())) {
            throw new IllegalArgumentException("Sandbox provider 只能使用内置地址");
        }
        return uri;
    }
    if (!QWEN.equals(normalized)) {
        throw new IllegalArgumentException("平台 provider 必须是 qwen 或 sandbox");
    }
    URI uri = validateTransport(ProviderUrlGuard.validate(baseUrl));
    if (!trustedOrigins.contains(origin(uri))) {
        throw new IllegalArgumentException("平台模型 base-url 不在受信 Qwen 地址范围内");
    }
    return uri;
}
```

Add `seedSandboxCapability("voice", "sandbox-speech-v1")` and `seedSandboxCapability("retrieval", "sandbox-embedding-v1")`; each first calls `repository.findCurrent(capability, ROLE_PRIMARY).hasElement()` and creates only when absent. Add both models to `PriceTableService.buildDefaultPrices()` with all numeric prices set to zero and capability `voice`/`retrieval`.

- [ ] **Step 4: Re-run focused tests and the control-plane regression tests**

Run the Step 2 command, then:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :services:intelligence-service:test --tests 'com.grassland.intelligence.ai.controlplane.*'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/ai platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/ai
git commit -m "feat(intelligence): #33 注册Sandbox语音与检索能力"
```

---

### Task 2: Preserve routing versions and support worker-owned AI Runs

**Files:**
- Modify: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/ai/byok/ByokRoutingService.java`
- Modify: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/creationcontext/FrozenAiConfigResolver.java`
- Modify: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/ai/run/AiExecutionService.java`
- Modify: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/security/IntelligenceException.java`
- Modify: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/security/IntelligenceErrorHandler.java`
- Test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/ai/byok/ByokRoutingServiceTest.java`
- Test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/creationcontext/FrozenAiConfigResolverTest.java`
- Create test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/ai/run/AiExecutionServiceWorkerTest.java`
- Create test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/security/IntelligenceErrorHandlerTest.java`

- [ ] **Step 1: Write failing tests for version keys, direct preparation and coded errors**

```java
assertThat(byok.modelVersionKey()).isEqualTo("byok:v7");
assertThat(platform.modelVersionKey()).isEqualTo("platform:4");

StepVerifier.create(execution.prepareExecution(
        "acct-1", "org-1", "retrieval", null, 40, 0, true))
        .assertNext(result -> assertThat(result.allowed()).isTrue())
        .verifyComplete();

ResponseEntity<Map<String, Object>> response = handler.handle(
        new IntelligenceException(503, "unsupported_provider", "暂不支持该模型供应商"));
assertThat(response.getBody()).containsEntry("code", "unsupported_provider");
assertThat(response.getBody()).containsEntry("error", "暂不支持该模型供应商");
```

- [ ] **Step 2: Run focused tests and confirm failure**

```bash
cd platform-java
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :services:intelligence-service:test --tests '*ByokRoutingServiceTest' --tests '*FrozenAiConfigResolverTest' --tests '*AiExecutionServiceWorkerTest' --tests '*IntelligenceErrorHandlerTest'
```

- [ ] **Step 3: Implement `ProviderResolution` version preservation**

Add `String keyVersion` to the record, pass `AiProviderKey.keyVersion()` from both live and frozen BYOK resolution, and add:

```java
public String modelVersionKey() {
    if (isPlatform()) return "platform:" + platformModelVersion;
    if (isByok()) return "byok:" + keyVersion;
    throw new IllegalStateException("拒绝结果没有模型版本");
}
```

Platform and denied constructors pass `null` for `keyVersion`. Update all 13 `ProviderResolution.byok/platform` construction sites and their tests in the same change.

- [ ] **Step 4: Extract a caller-independent AI preparation overload**

Keep the public exchange method as a resolver wrapper and move its body to:

```java
public Mono<ExecutionResult> prepareExecution(
        String accountId, String organizationId, String capability,
        CreditFeature feature, int estimatedInputTokens,
        int estimatedOutputTokens, boolean allowFallback) {
    return prepareExecution(accountId, organizationId, capability, feature,
            estimatedInputTokens, estimatedOutputTokens, allowFallback, null);
}
```

The private overload performs the existing routing, budget, price and charge steps. Do not create a synthetic `ServerWebExchange` in a worker.

- [ ] **Step 5: Add optional coded errors without breaking existing responses**

```java
public IntelligenceException(int status, String message) {
    this(status, null, message);
}

public IntelligenceException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
}
```

`IntelligenceErrorHandler` uses a `LinkedHashMap`; it always emits `success` and `error`, and emits `code` only when non-null.

- [ ] **Step 6: Re-run focused tests and full AI run tests**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :services:intelligence-service:test --tests 'com.grassland.intelligence.ai.run.*' --tests 'com.grassland.intelligence.ai.byok.*' --tests '*FrozenAiConfigResolverTest' --tests '*IntelligenceErrorHandlerTest'
```

Expected: PASS with existing uncoded error envelopes unchanged.

- [ ] **Step 7: Commit**

```bash
git add platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence
git commit -m "refactor(intelligence): #33 支持后台能力Run与路由版本"
```

---

### Task 3: Add speech and embedding persistence state machines

**Files:**
- Create: `platform-java/services/intelligence-service/src/main/resources/db/migration/V31__speech_transcription.sql`
- Create: `platform-java/services/intelligence-service/src/main/resources/db/migration/V32__content_asset_embedding.sql`
- Create: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/speech/SpeechTranscription.java`
- Create: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/speech/SpeechTranscriptionRepository.java`
- Create: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/embedding/ContentAssetEmbedding.java`
- Create: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/embedding/ContentAssetEmbeddingRepository.java`
- Create test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/speech/SpeechTranscriptionRepositoryIT.java`
- Create test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/embedding/ContentAssetEmbeddingRepositoryIT.java`

- [ ] **Step 1: Write repository integration tests before migrations**

Cover these exact transitions:

```java
// speech: createProcessing -> storeProviderResult -> markCompleted; owner-scoped find hides another account.
// speech: markFailed stores only failureCode and clears transcript text.
// embedding: enqueue is idempotent for asset/version/hash while pending.
// embedding: claimBatch uses a token and increments attemptCount once under two concurrent claimers.
// embedding: markReady persists a 256-number JSON array and routing snapshot.
// embedding: stale current row is excluded by findReadyForAssets.
// embedding: expired processing lease can be reclaimed; max-attempt rows cannot.
```

- [ ] **Step 2: Run both repository tests and confirm Flyway/table failure**

```bash
cd platform-java
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :services:intelligence-service:test --tests '*SpeechTranscriptionRepositoryIT' --tests '*ContentAssetEmbeddingRepositoryIT'
```

- [ ] **Step 3: Create V31 with strict completion invariants**

```sql
CREATE TABLE speech_transcription (
    id uuid PRIMARY KEY,
    media_reference_id uuid NOT NULL,
    owner_account_id text NOT NULL,
    organization_id text,
    requested_language varchar(16) NOT NULL,
    detected_language varchar(16),
    duration_ms bigint NOT NULL CHECK (duration_ms >= 0),
    status varchar(16) NOT NULL CHECK (status IN ('processing','completed','failed')),
    transcript_text text,
    provider varchar(64),
    model varchar(128),
    platform_model_version integer,
    ai_run_id uuid,
    failure_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CHECK (status <> 'completed' OR
           (transcript_text IS NOT NULL AND provider IS NOT NULL AND model IS NOT NULL
            AND ai_run_id IS NOT NULL AND completed_at IS NOT NULL)),
    CHECK (status <> 'failed' OR failure_code IS NOT NULL)
);
CREATE INDEX idx_speech_transcription_owner
    ON speech_transcription(owner_account_id, created_at DESC);
CREATE INDEX idx_speech_transcription_media
    ON speech_transcription(media_reference_id, created_at DESC);
```

- [ ] **Step 4: Create V32 with partial uniqueness and lease claims**

The table contains `asset_id`, `asset_version`, `content_hash`, `status`, nullable routing snapshot fields, `algorithm_version`, `dimensions`, `embedding jsonb`, `ai_run_id`, `failure_code`, `attempt_count`, `next_attempt_at`, `claim_token`, `claimed_until`, and timestamps. Add:

```sql
CREATE UNIQUE INDEX uq_content_asset_embedding_pending
    ON content_asset_embedding(asset_id, asset_version, content_hash)
    WHERE status IN ('pending','processing');
CREATE UNIQUE INDEX uq_content_asset_embedding_ready_model
    ON content_asset_embedding(
        asset_id, asset_version, content_hash, provider, model,
        model_version_key, algorithm_version)
    WHERE status = 'ready';
CREATE INDEX idx_content_asset_embedding_claim
    ON content_asset_embedding(next_attempt_at, created_at)
    WHERE status IN ('pending','failed');
```

Ready rows require a JSON array, positive dimensions, non-null routing fields and completion time. Failed rows require `failure_code`; attempts are non-negative.

- [ ] **Step 5: Implement records and repositories with token-checked updates**

Required repository surface:

```java
Mono<SpeechTranscription> createProcessing(SpeechTranscription value);
Mono<SpeechTranscription> findOwned(UUID id, String ownerAccountId);
Mono<Boolean> storeProviderResult(UUID id, String text, String detectedLanguage,
        String provider, String model, Integer platformModelVersion, UUID runId);
Mono<Boolean> markCompleted(UUID id);
Mono<Boolean> markFailed(UUID id, String failureCode);

Mono<Boolean> enqueue(UUID assetId, int version, String contentHash);
Flux<ContentAssetEmbedding> claimBatch(int limit, UUID claimToken, Duration lease, int maxAttempts);
Mono<Boolean> markReady(UUID id, UUID claimToken, ProviderResolution provider,
        String algorithmVersion, List<Double> vector, UUID runId);
Mono<Boolean> markFailed(UUID id, UUID claimToken, String failureCode, Duration delay);
Mono<Boolean> markStale(UUID id, UUID claimToken);
Flux<ContentAssetEmbedding> findReadyForAssets(Collection<UUID> assetIds);
```

All worker completion methods require both `id` and `claim_token`, so an expired worker cannot overwrite a newer claim.

- [ ] **Step 6: Re-run both integration tests and all Flyway-backed intelligence tests**

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add platform-java/services/intelligence-service/src/main/resources/db/migration/V31__speech_transcription.sql platform-java/services/intelligence-service/src/main/resources/db/migration/V32__content_asset_embedding.sql platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/speech platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/embedding platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/speech platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/embedding
git commit -m "feat(intelligence): #33 语音与向量索引持久化状态机"
```

---

### Task 4: Add controlled speech audio media and duration probing

**Files:**
- Modify: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/media/MediaPurpose.java`
- Modify: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/media/MediaController.java`
- Create: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/speech/SpeechAudioPolicy.java`
- Create: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/speech/AudioDurationProbe.java`
- Test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/media/MediaControllerIT.java`
- Create test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/speech/SpeechAudioPolicyTest.java`
- Create test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/speech/AudioDurationProbeTest.java`

- [ ] **Step 1: Write failing MIME, size, signature and duration tests**

```java
assertThat(SpeechAudioPolicy.hasExpectedSignature("audio/wav", riffWaveBytes())).isTrue();
assertThat(SpeechAudioPolicy.hasExpectedSignature("audio/ogg", oggBytes())).isTrue();
assertThat(SpeechAudioPolicy.hasExpectedSignature("audio/webm", ebmlBytes())).isTrue();
assertThat(SpeechAudioPolicy.hasExpectedSignature("audio/mpeg", pngBytes())).isFalse();
assertThat(AudioDurationProbe.parseDurationMillis("12.345\n")).isEqualTo(12_345L);
```

Extend `MediaControllerIT` to prove `speech_audio` accepts the six spec MIME types, rejects PDF/video, rejects 25 MiB + 1 byte, confirms a signature-valid fixture, and refuses a MIME/signature mismatch.

- [ ] **Step 2: Run focused tests and confirm failure**

```bash
cd platform-java
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :services:intelligence-service:test --tests '*SpeechAudioPolicyTest' --tests '*AudioDurationProbeTest' --tests '*MediaControllerIT'
```

- [ ] **Step 3: Implement `speech_audio` ticket policy**

Add `SPEECH_AUDIO("speech_audio")`, MIME set `{audio/mpeg,audio/mp4,audio/wav,audio/x-wav,audio/webm,audio/ogg}`, a 25 MiB cap, and extensions `mp3/m4a/wav/webm/ogg`. In `MediaController.validate`, apply the purpose-specific MIME and size checks before returning `UploadSpec`. In `validateDownloadedBytes`, call `SpeechAudioPolicy` when purpose is `speech_audio`.

- [ ] **Step 4: Implement signature checks and `ffprobe`**

`SpeechAudioPolicy` recognizes: RIFF+WAVE, OggS, EBML `1A45DFA3`, MP4 `ftyp` at bytes 4-7, ID3, or a valid MPEG frame sync. `AudioDurationProbe` writes bytes to its configured temp directory, invokes:

```text
ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 <file>
```

It has a 15-second process timeout, parses finite positive seconds to milliseconds, caps numeric overflow, and deletes the temporary file in `finally`. The service calls it on bounded elastic in Task 5.

- [ ] **Step 5: Re-run tests and confirm PASS**

- [ ] **Step 6: Commit**

```bash
git add platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/media platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/speech platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/media platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/speech
git commit -m "feat(intelligence): #33 受控语音音频上传与探测"
```

---

### Task 5: Deliver the authenticated Sandbox transcription API

**Files:**
- Create: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/speech/SpeechRecognitionProvider.java`
- Create: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/speech/SandboxSpeechRecognitionProvider.java`
- Create: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/speech/SpeechProviderRegistry.java`
- Create: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/speech/SpeechTranscriptionService.java`
- Create: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/speech/SpeechTranscriptionController.java`
- Create test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/speech/SpeechProviderContractTest.java`
- Create test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/speech/SpeechTranscriptionControllerIT.java`

- [ ] **Step 1: Write provider contract and API integration tests**

Provider contract:

```java
SpeechRecognitionProvider.Command command = new SpeechRecognitionProvider.Command(
        mediaId, "sha256-value", "zh-CN", 12_000, wavBytes());
SpeechRecognitionProvider.Result first = sandbox.transcribe(command).block();
SpeechRecognitionProvider.Result second = sandbox.transcribe(command).block();
assertThat(first).isEqualTo(second);
assertThat(first.text()).startsWith("[Sandbox]");
assertThat(first.sandbox()).isTrue();
```

Controller IT covers: 201 round trip, GET owner round trip, no identity 401, another owner 404, wrong purpose/pending/expired/deleted 404, invalid language 400, over-duration 400, no model 503 `no_platform_model`, unsupported Provider 503 `unsupported_provider`, zero-cost completed `ai_run`, and Provider failure causing failed transcription plus failed Run.

- [ ] **Step 2: Run tests and confirm missing classes/endpoints**

```bash
cd platform-java
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :services:intelligence-service:test --tests '*SpeechProviderContractTest' --tests '*SpeechTranscriptionControllerIT'
```

- [ ] **Step 3: Implement Provider boundary and registry**

```java
public interface SpeechRecognitionProvider {
    String provider();
    Mono<Result> transcribe(Command command);

    record Command(UUID mediaId, String checksum, String language,
                   long durationMs, byte[] audio) {}
    record Result(String text, String detectedLanguage,
                  int inputTokens, int outputTokens, boolean sandbox) {}
}
```

`SpeechProviderRegistry.require(name)` returns the matching bean or throws coded `IntelligenceException(503, "unsupported_provider", "暂不支持该语音模型供应商")`. Sandbox text contains the requested language and the first 12 checksum characters, never audio bytes.

- [ ] **Step 4: Implement service orchestration**

Exact order: require user; load media owner-scoped; validate purpose/state/expiry/MIME/size; read bytes and probe duration on bounded elastic; reject >900,000 ms; create processing row; call `AiExecutionService.prepareExecution(...,"voice",null,0,0,true)`; acquire `PlatformConcurrencyLimiter`; select Provider; transcribe; transactionally store result, settle Run at zero cost, and mark completed. On any post-Run failure, transactionally mark failed with a stable code and call `handleFailure`.

Use `Mono.usingWhen` to always release the concurrency lease. Do not log `Result.text()`.

- [ ] **Step 5: Implement controller contract**

```java
@PostMapping("/api/speech/transcriptions")
public Mono<ResponseEntity<Map<String, Object>>> create(
        @RequestBody CreateTranscriptionRequest body, ServerWebExchange exchange) {
    return service.transcribe(body.mediaId(), body.language(), exchange)
            .map(value -> ResponseEntity.status(201).body(success(toResponse(value))));
}

@GetMapping("/api/speech/transcriptions/{id}")
public Mono<Map<String, Object>> get(@PathVariable UUID id, ServerWebExchange exchange) {
    return service.findOwned(id, exchange).map(SpeechTranscriptionController::toResponse)
            .map(SpeechTranscriptionController::success);
}
```

Allow only `auto`, `zh-CN`, and `en-US`; default blank/null to `auto`.

- [ ] **Step 6: Re-run speech tests and intelligence AI/media regressions**

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/speech platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/speech
git commit -m "feat(intelligence): #33 Sandbox语音转写API闭环"
```

---

### Task 6: Add method-scoped Edge speech routing

**Files:**
- Modify: `platform-java/services/edge-bff/src/main/resources/application.yml`
- Modify: `docker-compose.yml`
- Modify: `platform-java/services/edge-bff/src/test/java/com/grassland/edge/proxy/JavaRouteManifestGateTest.java`
- Modify: `platform-java/services/edge-bff/src/test/java/com/grassland/edge/proxy/EdgeFailClosedIT.java`

- [ ] **Step 1: Write failing route and flag tests**

Add representative POST and GET routes, add `PUT /api/speech/transcriptions/id` to fail-closed boundaries, and configure `EDGE_ROUTE_SPEECH_INTELLIGENCE=false` in `EdgeFailClosedIT` with assertions that both allowed methods return 404 when disabled.

- [ ] **Step 2: Run Edge tests and confirm failure**

```bash
cd platform-java
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :services:edge-bff:test --tests '*JavaRouteManifestGateTest' --tests '*EdgeFailClosedIT'
```

- [ ] **Step 3: Register two routes with the same flag**

```yaml
- method: POST
  path: /api/speech
  upstream: intelligence
  enabled: ${EDGE_ROUTE_SPEECH_INTELLIGENCE:true}
- method: GET
  path: /api/speech
  upstream: intelligence
  enabled: ${EDGE_ROUTE_SPEECH_INTELLIGENCE:true}
```

Add `EDGE_ROUTE_SPEECH_INTELLIGENCE: ${EDGE_ROUTE_SPEECH_INTELLIGENCE:-true}` to Edge Compose environment.

- [ ] **Step 4: Re-run Edge tests and confirm PASS**

- [ ] **Step 5: Commit**

```bash
git add platform-java/services/edge-bff docker-compose.yml
git commit -m "feat(edge): #33 路由语音转写并保持关闭失败"
```

---

### Task 7: Implement deterministic Embedding and semantic ranking math

**Files:**
- Create: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/embedding/EmbeddingProvider.java`
- Create: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/embedding/SandboxEmbeddingProvider.java`
- Create: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/embedding/EmbeddingProviderRegistry.java`
- Create: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/embedding/EmbeddingTextNormalizer.java`
- Create: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/embedding/CosineSimilarity.java`
- Create: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/embedding/SemanticRanker.java`
- Create test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/embedding/SandboxEmbeddingProviderTest.java`
- Create test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/embedding/EmbeddingTextNormalizerTest.java`
- Create test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/embedding/SemanticRankerTest.java`

- [ ] **Step 1: Write failing deterministic vector and ranking tests**

```java
assertThat(provider.embed("开业 门店 咖啡").block().vector()).hasSize(256);
assertThat(provider.embed("开业 门店 咖啡").block().vector())
        .containsExactlyElementsOf(provider.embed("开业 门店 咖啡").block().vector());
assertThat(norm(provider.embed("开业 门店 咖啡").block().vector())).isCloseTo(1.0, within(1e-9));
assertThat(similarity("开业 门店", "门店 开业 海报"))
        .isGreaterThan(similarity("开业 门店", "宠物 医疗"));
assertThat(SemanticRanker.combine(90, 70)).isEqualTo(82);
assertThat(SemanticRanker.rulesOnlyInSemanticRun(70)).isEqualTo(28);
```

Also reject empty text, NaN, Infinity, zero norm and mismatched dimensions.

- [ ] **Step 2: Run focused tests and confirm failure**

- [ ] **Step 3: Implement the Provider contract and deterministic projection**

```java
public interface EmbeddingProvider {
    String provider();
    String algorithmVersion();
    int dimensions();
    Mono<Result> embed(String normalizedText);
    record Result(List<Double> vector, int inputTokens, boolean sandbox) {}
}
```

Normalize tokens, hash each token with SHA-256, select dimensions and signs from digest bytes, add token frequency weights, then L2-normalize. Use only JDK crypto/math; no random seed or external service.

- [ ] **Step 4: Implement canonical asset text and scoring**

`EmbeddingTextNormalizer.forAsset(ContentAsset)` emits title/category/sorted tags/source/license lines, collapses whitespace, lowercases ASCII, and returns both normalized text and SHA-256 hash. `CosineSimilarity` validates vectors. `SemanticRanker` implements 0-100 normalization, 60/40 fusion and stable comparator `finalScore DESC, ruleScore DESC, updatedAt DESC, id ASC`.

- [ ] **Step 5: Run all embedding unit tests and confirm PASS**

- [ ] **Step 6: Commit**

```bash
git add platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/embedding platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/embedding
git commit -m "feat(intelligence): #33 确定性Embedding与语义排序算法"
```

---

### Task 8: Build reliable material indexing and mutation hooks

**Files:**
- Create: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/embedding/EmbeddingIndexProperties.java`
- Create: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/embedding/EmbeddingExecutionService.java`
- Create: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/embedding/EmbeddingIndexWorker.java`
- Modify: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/contentlibrary/ContentAssetController.java`
- Modify: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/contentlibrary/ContentAssetAdminController.java`
- Modify: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/contentlibrary/ContentAssetRepository.java`
- Modify: `platform-java/services/intelligence-service/src/main/resources/application.yml`
- Create test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/embedding/EmbeddingExecutionServiceTest.java`
- Create test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/embedding/EmbeddingIndexWorkerIT.java`
- Test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/contentlibrary/ContentAssetControllerIT.java`

- [ ] **Step 1: Write failing worker, backfill and mutation tests**

Cover: active create enqueues in the same transaction; edit enqueues new version and stales old; public approval enqueues; delete/reject stales current; worker produces ready 256-vector and completed retrieval Run; Provider failure records stable code and backoff; max attempts stop claims; stale lease reclaims; content/model drift causes a new row; inactive or changed asset causes claimed row to become stale; scanner processes at most configured batch size.

- [ ] **Step 2: Run focused tests and confirm failure**

- [ ] **Step 3: Implement bounded properties**

```yaml
ai:
  embedding-index:
    enabled: ${EMBEDDING_INDEX_ENABLED:true}
    poll-interval-ms: ${EMBEDDING_INDEX_POLL_INTERVAL_MS:3000}
    batch-size: ${EMBEDDING_INDEX_BATCH_SIZE:20}
    backfill-batch-size: ${EMBEDDING_INDEX_BACKFILL_BATCH_SIZE:100}
    claim-lease: ${EMBEDDING_INDEX_CLAIM_LEASE:PT1M}
    max-attempts: ${EMBEDDING_INDEX_MAX_ATTEMPTS:5}
```

Validate batch sizes 1-500, positive lease, and attempts 1-20.

- [ ] **Step 4: Implement `EmbeddingExecutionService`**

For HTTP query use the exchange preparation overload; for indexing use account/org direct preparation. Both prepare `retrieval` with `CreditFeature=null`, acquire/release concurrency, require a Provider from the registry, validate the returned vector, settle with Provider input tokens and zero output tokens, and return vector plus Provider/model/version/Run metadata. Unsupported providers fail with coded 503.

- [ ] **Step 5: Implement worker claims and backfill**

`@Scheduled` calls a public `runOnce()` for deterministic tests. It first loads at most `backfillBatchSize` active assets through a new repository query, normalizes them and idempotently enqueues missing hashes; then claims at most `batchSize` rows. Processing reloads the asset and compares status/version/hash before any Run. Success uses claim-token `markReady`; failure calls `AiExecutionService.handleFailure` when a Run exists, stores a sanitized code, and applies exponential delay capped at one hour.

- [ ] **Step 6: Wire transactional mutation hooks**

Inject one `ContentAssetEmbeddingRepository`/normalizer facade. In create/update/public approve transactions, enqueue the returned active asset before commit. In delete/reject, mark all ready/pending rows stale. Index enqueue failure must roll back the metadata mutation only when the database write itself fails; Provider execution is never in the CRUD transaction.

- [ ] **Step 7: Re-run focused tests and content-library regressions**

Expected: PASS; CRUD responses do not wait for Provider calls.

- [ ] **Step 8: Commit**

```bash
git add platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/embedding platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/contentlibrary platform-java/services/intelligence-service/src/main/resources/application.yml platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/embedding platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/contentlibrary
git commit -m "feat(intelligence): #33 可靠维护素材Embedding索引"
```

---

### Task 9: Integrate semantic retrieval after authorization

**Files:**
- Modify: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/contentlibrary/ContentAssetController.java`
- Modify: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/contentlibrary/ContentAssetRecommendationService.java`
- Modify: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/contentlibrary/ContentAssetRecommender.java`
- Modify: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/embedding/ContentAssetEmbeddingRepository.java`
- Test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/contentlibrary/ContentAssetRecommenderTest.java`
- Test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/contentlibrary/ContentAssetControllerIT.java`

- [ ] **Step 1: Write failing compatibility, ranking, fallback and isolation tests**

Required cases: independent mode without query exactly preserves current score/order/response semantics; query length 0/501 rejects 400; explicit query applies semantic scores; task mode derives semantic text from authoritative Marketplace payload when query absent; explicit query overrides derived text; query Provider failure returns 200 current rule order with `semantic.status=fallback`; stale/missing vector uses `round(0.4*ruleScore)` only during applied semantic runs; personal/org/store/grant/public authorization matrix excludes inaccessible IDs before `findReadyForAssets`; at most 500 candidate IDs reach vector lookup; output limit remains 1-50.

- [ ] **Step 2: Run recommendation tests and confirm failure**

- [ ] **Step 3: Extend request and response records**

Add `query` to controller and service request. Add `ruleScore`, nullable `semanticScore`, and semantic metadata:

```java
public record SemanticMetadata(
        String status, String provider, String model,
        boolean sandbox, String message) {
    static SemanticMetadata notRequested() {
        return new SemanticMetadata("not_requested", null, null, false, null);
    }
}
```

Do not emit nullable Provider/model/message keys when absent; never emit the raw query or vector.

- [ ] **Step 4: Enforce permission-first bounded retrieval**

Retain existing candidate source logic, deduplicate deterministically, then `.take(500).collectList()`. Only after that list exists may the service call `EmbeddingExecutionService.embedQuery` and `findReadyForAssets(candidateIds)`. Match rows on asset ID, current asset version, current content hash and execution model-version key.

- [ ] **Step 5: Apply semantic ranking or full fallback**

When query embedding succeeds, add per-item semantic/rule scores and reasons and use `SemanticRanker`. A missing current vector receives rules-only-in-semantic-run scoring. Any query embedding/model/budget/Provider error is caught at this orchestration boundary, logged without query text, and returns the untouched current recommender result with `fallback` metadata.

- [ ] **Step 6: Re-run content-library tests and confirm PASS**

- [ ] **Step 7: Commit**

```bash
git add platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/contentlibrary platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/embedding platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/contentlibrary
git commit -m "feat(intelligence): #33 权限内语义素材检索与降级"
```

---

### Task 10: Add frontend speech and semantic API contracts

**Files:**
- Modify: `src/types/grassland/media.ts`
- Modify: `src/composables/useGrasslandGovernance.ts`
- Test: `src/composables/useGrassland.test.ts`

- [ ] **Step 1: Write failing composable tests**

Assert speech upload sends `purpose=speech_audio`, uses ticket PUT and confirm, transcribe POST sends `{mediaId,language}`, GET encodes ID, recommendation sets `query`, and no-query requests omit it. Verify each upload/API failure returns null through the existing `run()` error channel.

- [ ] **Step 2: Run the focused frontend test and confirm failure**

```bash
npm test -- --run src/composables/useGrassland.test.ts
```

- [ ] **Step 3: Add exact TypeScript wire types**

```ts
export interface SpeechTranscription {
  id: string
  mediaId: string
  status: 'processing' | 'completed' | 'failed'
  text: string | null
  language: 'auto' | 'zh-CN' | 'en-US'
  durationMs: number
  provider: string | null
  model: string | null
  modelVersion: number | null
  aiRunId: string | null
  sandbox: boolean
  createdAt: string | null
  completedAt: string | null
}

export interface SemanticRecommendationMetadata {
  status: 'not_requested' | 'applied' | 'fallback'
  provider?: string
  model?: string
  sandbox?: boolean
  message?: string
}
```

Add `speech_audio` to `MediaPurpose`, `query?: string` to input, `ruleScore` and optional `semanticScore` to result items, and `semantic` to the query result.

- [ ] **Step 4: Implement composable methods**

Add `uploadSpeechAudio(file)`, `createSpeechTranscription(mediaId, language)`, `getSpeechTranscription(id)`, and pass trimmed `query` through `recommendContentAssets`. Reuse `putToPresignedUrl`; do not introduce a second upload implementation.

- [ ] **Step 5: Re-run tests and typecheck**

```bash
npm test -- --run src/composables/useGrassland.test.ts
npm run typecheck
```

- [ ] **Step 6: Commit**

```bash
git add src/types/grassland/media.ts src/composables/useGrasslandGovernance.ts src/composables/useGrassland.test.ts
git commit -m "feat(frontend): #33 语音与语义检索API客户端"
```

---

### Task 11: Build the AI center speech workspace

**Files:**
- Create: `src/components/SpeechTranscriptionPanel.vue`
- Create test: `src/components/SpeechTranscriptionPanel.test.ts`
- Modify: `src/views/ai-center/AiCreationCenter.vue`
- Modify test: `src/components/AiCreationCenter.test.ts`

- [ ] **Step 1: Write failing component tests**

Cover: accept list and single-file size validation; file selection preview; upload then transcribe request order; disabled duplicate submit while pending; success renders Sandbox label/language/duration/text; copy calls `navigator.clipboard.writeText`; failure keeps selected media and enables retry; remove resets state; unauthenticated AI center speech tab emits `request-login`; authenticated tab lazy-mounts the panel.

- [ ] **Step 2: Run component tests and confirm failure**

```bash
npm test -- --run src/components/SpeechTranscriptionPanel.test.ts src/components/AiCreationCenter.test.ts
```

- [ ] **Step 3: Implement the focused panel**

Use one native file input with accept `.mp3,.m4a,.wav,.webm,.ogg,audio/mpeg,audio/mp4,audio/wav,audio/webm,audio/ogg`, a language select, upload/remove commands, a transcribe command, an `aria-live` status, result textarea/read-only text area and copy button. Keep selected/confirmed media after transcription errors. Never render object key, upload URL, Run internals or vectors.

- [ ] **Step 4: Add the authenticated AI center section**

Extend the union with `speech`, add `{id:'speech',label:'语音转写'}` between assistant and runs, render `SpeechTranscriptionPanel` in its own branch, and keep keys as the explicit final branch instead of a broad `v-else`.

- [ ] **Step 5: Re-run tests, typecheck and build**

```bash
npm test -- --run src/components/SpeechTranscriptionPanel.test.ts src/components/AiCreationCenter.test.ts
npm run typecheck
npm run build
```

- [ ] **Step 6: Commit**

```bash
git add src/components/SpeechTranscriptionPanel.vue src/components/SpeechTranscriptionPanel.test.ts src/views/ai-center/AiCreationCenter.vue src/components/AiCreationCenter.test.ts
git commit -m "feat(frontend): #33 AI中心语音转写工作区"
```

---

### Task 12: Add semantic search and explanations to the material library

**Files:**
- Modify: `src/components/MediaLibraryPanel.vue`
- Modify test: `src/components/MediaLibraryPanel.test.ts`

- [ ] **Step 1: Write failing semantic UI tests**

Cover: query input is visible only in smart recommendations; submit sends trimmed query; task mode can search with an empty explicit query; applied results show total/rule/semantic scores and reasons; fallback message is non-blocking and rule results remain selectable; no results and request failure have distinct text; a second search replaces stale scores; Enter submits without reloading the page.

- [ ] **Step 2: Run focused tests and confirm failure**

```bash
npm test -- --run src/components/MediaLibraryPanel.test.ts
```

- [ ] **Step 3: Implement query and response state**

Add `semanticQuery`, `recommendationLoading`, and typed semantic metadata. Split recommendation loading from generic `refresh()` into `searchRecommendations()` so changing input does not wipe other library tabs. Preserve the task/application context on every request.

- [ ] **Step 4: Render stable semantic controls and explanations**

Use a compact search form, 500-character input, submit button, aria-live fallback notice, and score line:

```text
匹配度 82 · 规则 70 · 语义 90
```

If `semanticScore` is absent, omit the semantic fragment. Existing checkbox selection and download/edit commands remain unchanged.

- [ ] **Step 5: Re-run test, typecheck and build**

```bash
npm test -- --run src/components/MediaLibraryPanel.test.ts
npm run typecheck
npm run build
```

- [ ] **Step 6: Commit**

```bash
git add src/components/MediaLibraryPanel.vue src/components/MediaLibraryPanel.test.ts
git commit -m "feat(frontend): #33 素材语义搜索与可解释得分"
```

---

### Task 13: Verify end to end and reconcile documentation

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/架构/草场旧API兼容契约矩阵.md`
- Modify: `docs/草场开发进度与续接指南.md`
- Modify: `项目速览.md`
- Modify: `docs/superpowers/specs/2026-08-18-speech-embedding-design.md`

- [ ] **Step 1: Run full Java verification**

```bash
cd platform-java
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :services:intelligence-service:test :services:edge-bff:test
```

Expected: all tests PASS, including Testcontainers repository/API tests.

- [ ] **Step 2: Run full frontend verification**

```bash
cd ..
npm test
npm run typecheck
npm run build
```

Expected: Vitest, TypeScript and Vite all exit 0.

- [ ] **Step 3: Run repository hygiene checks**

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only intended #33 changes plus the preserved/merged user documentation change.

- [ ] **Step 4: Update documentation from verified facts**

Add `/api/speech/transcriptions` and semantic recommendation fields to `CLAUDE.md` and the compatibility matrix. Mark #33 Sandbox chain implemented in the progress guide and `项目速览.md`, while keeping real Provider integration explicitly pending. Set the design spec status to `已实现（Sandbox-first，2026-08-18）` and record the exact test commands, not commit SHAs.

- [ ] **Step 5: Commit documentation**

```bash
git add CLAUDE.md docs/架构/草场旧API兼容契约矩阵.md docs/草场开发进度与续接指南.md 项目速览.md docs/superpowers/specs/2026-08-18-speech-embedding-design.md
git commit -m "docs: #33 回写语音与语义检索开发状态"
```

- [ ] **Step 6: Start the frontend for manual verification**

```bash
npm run dev -- --host 127.0.0.1
```

Keep the server running, report the selected local URL, and manually verify desktop and mobile layouts for the speech panel and material semantic search before requesting integration review.
