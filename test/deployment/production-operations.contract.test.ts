import { execFileSync, spawnSync } from 'node:child_process'
import { chmodSync, mkdtempSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')
const BACKUP_SCRIPT = resolve(REPOSITORY_ROOT, 'scripts/backup-restore-drill.sh')
const RELEASE_SCRIPT = resolve(REPOSITORY_ROOT, 'scripts/production-release.sh')
const FAILURE_DRILL_SCRIPT = resolve(REPOSITORY_ROOT, 'scripts/production-failure-drill.sh')
const VIDEO_WEBHOOK_DRILL_SCRIPT = resolve(REPOSITORY_ROOT, 'scripts/video-webhook-drill.sh')
const VIDEO_ARCHIVE_DRILL_SCRIPT = resolve(REPOSITORY_ROOT, 'scripts/video-archive-reconciliation-drill.sh')
const VIDEO_EVIDENCE_VALIDATOR = resolve(REPOSITORY_ROOT, 'scripts/validate-video-production-evidence.sh')
const CANARY_EVIDENCE_VALIDATOR = resolve(REPOSITORY_ROOT, 'scripts/validate-production-canary-evidence.sh')
const FAILURE_EVIDENCE_VALIDATOR = resolve(REPOSITORY_ROOT, 'scripts/validate-production-failure-evidence.sh')
const OBSERVABILITY_EVIDENCE_VALIDATOR = resolve(REPOSITORY_ROOT, 'scripts/validate-observability-evidence.sh')
const ROTATION_EVIDENCE_VALIDATOR = resolve(REPOSITORY_ROOT, 'scripts/validate-identity-key-rotation-evidence.sh')
const CREDENTIAL_EVIDENCE_VALIDATOR = resolve(REPOSITORY_ROOT, 'scripts/validate-credential-rotation-evidence.sh')
const CREDENTIAL_EVIDENCE_CREATOR = resolve(REPOSITORY_ROOT, 'scripts/create-credential-rotation-evidence.sh')
const SECRET_MATERIALIZER = resolve(REPOSITORY_ROOT, 'scripts/materialize-production-secrets.sh')
const VIDEO_DRILL_INPUTS_SCRIPT = resolve(REPOSITORY_ROOT, 'scripts/validate-video-production-drill-inputs.sh')
const FINANCE_POLICY_SCRIPT = resolve(REPOSITORY_ROOT, 'scripts/validate-finance-credits-cents-policy.sh')
const COMPOSE_VALIDATOR = resolve(REPOSITORY_ROOT, 'scripts/validate-production-compose.sh')
const SECRET_VALIDATOR = resolve(REPOSITORY_ROOT, 'scripts/validate-production-secrets.sh')
const SMOKE_SCRIPT = resolve(REPOSITORY_ROOT, 'scripts/production-smoke.sh')
const IMAGE_SECURITY_SCRIPT = resolve(REPOSITORY_ROOT, 'scripts/ci-image-security.sh')
const PROVENANCE_SCRIPT = resolve(REPOSITORY_ROOT, 'scripts/validate-image-provenance.sh')
const IMAGE_EVIDENCE_SCRIPT = resolve(REPOSITORY_ROOT, 'scripts/validate-image-security-evidence.sh')
const CANARY_PLAN_SCRIPT = resolve(REPOSITORY_ROOT, 'scripts/production-canary-plan.sh')
const OBSERVABILITY_VALIDATOR = resolve(REPOSITORY_ROOT, 'scripts/validate-observability-config.sh')
const OBSERVABILITY_COMPOSE = resolve(REPOSITORY_ROOT, 'docker-compose.observability.yml')
const LOCAL_OTEL_SMOKE = resolve(REPOSITORY_ROOT, 'scripts/local-otel-trace-smoke.sh')
const MIGRATION_VALIDATOR = resolve(REPOSITORY_ROOT, 'scripts/validate-released-migrations.sh')
const MIGRATION_MANIFEST = resolve(REPOSITORY_ROOT, 'platform-java/deploy/released-migrations.sha256')
const DOTENV_LOADER = resolve(REPOSITORY_ROOT, 'scripts/lib/dotenv.sh')
const temporaryDirectories: string[] = []

function temporaryDirectory(): string {
  const directory = mkdtempSync(join(tmpdir(), 'grassland-production-operations-'))
  temporaryDirectories.push(directory)
  return directory
}

function sha256(path: string): string {
  return execFileSync('shasum', ['-a', '256', path], { encoding: 'utf8' }).split(/\s+/)[0]
}

function validBackup(): { root: string; manifest: string } {
  const root = temporaryDirectory()
  const mediaDirectory = join(root, 'minio/grassland')
  mkdirSync(mediaDirectory, { recursive: true })
  const dump = join(root, 'postgres.dump')
  const media = join(mediaDirectory, 'asset.bin')
  writeFileSync(dump, 'database-backup')
  writeFileSync(media, 'media-backup')
  const manifest = join(root, 'manifest.txt')
  writeFileSync(manifest, [
    '# grassland backup manifest v1',
    'created_at=2026-08-13T00:00:00Z',
    'postgres_dump=postgres.dump',
    'minio_prefix=minio/grassland',
    'redis_replay_keys=excluded',
    'kafka_temporal=deployment_side',
    `sha256 ${sha256(media)} minio/grassland/asset.bin`,
    `sha256 ${sha256(dump)} postgres.dump`,
    '',
  ].join('\n'))
  return { root, manifest }
}

function productionComposeEnvironment(overrides: NodeJS.ProcessEnv = {}): NodeJS.ProcessEnv {
  const root = temporaryDirectory()
  const truststore = join(root, 'kafka-truststore.p12')
  const temporalCert = join(root, 'temporal-client.crt')
  const temporalKey = join(root, 'temporal-client.key')
  writeFileSync(truststore, 'test-truststore')
  writeFileSync(temporalCert, 'test-certificate')
  writeFileSync(temporalKey, 'test-private-key', { mode: 0o600 })
  return {
    ...process.env,
    MINIO_ROOT_USER: 'test-minio-root',
    MINIO_ROOT_PASSWORD: 'test-minio-root-secret',
    MINIO_ACCESS_KEY: 'test-minio-app',
    MINIO_SECRET_KEY: 'test-minio-app-secret',
    KAFKA_BOOTSTRAP_SERVERS: 'kafka-a.internal:9093,kafka-b.internal:9093',
    KAFKA_USERNAME: 'test-kafka-user',
    KAFKA_PASSWORD: 'test-kafka-password',
    KAFKA_SSL_TRUSTSTORE_PASSWORD: 'test-truststore-password',
    KAFKA_SSL_TRUSTSTORE_FILE: truststore,
    TEMPORAL_TARGET: 'temporal.internal:7233',
    TEMPORAL_NAMESPACE: 'grassland-production',
    TEMPORAL_MTLS_SERVER_NAME: 'temporal.internal',
    TEMPORAL_MTLS_CERT_CHAIN_FILE: temporalCert,
    TEMPORAL_MTLS_KEY_FILE: temporalKey,
    VIDEO_GENERATION_MODE: 'seedance',
    QWEN_BASE_URL: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    QWEN_API_KEY: 'test-qwen-api-key-for-compose',
    AI_PROVIDER_ALLOW_SANDBOX: 'false',
    AI_SPEECH_PROVIDER: 'openai-compatible',
    AI_SPEECH_BASE_URL: 'https://api.openai.com/v1',
    AI_SPEECH_API_KEY: 'test-speech-api-key-for-compose',
    AI_SPEECH_MODEL: 'test-speech-model',
    AI_SPEECH_TRANSCRIPTION_PATH: '/audio/transcriptions',
    AI_SPEECH_CENTS_PER_SECOND: '1',
    AI_EMBEDDING_PROVIDER: 'openai-compatible',
    AI_EMBEDDING_BASE_URL: 'https://api.openai.com/v1',
    AI_EMBEDDING_API_KEY: 'test-embedding-api-key-for-compose',
    AI_EMBEDDING_MODEL: 'test-embedding-model',
    AI_EMBEDDING_PATH: '/embeddings',
    AI_EMBEDDING_DIMENSIONS: '256',
    AI_EMBEDDING_SEND_DIMENSIONS: 'true',
    AI_EMBEDDING_CENTS_PER_1K_INPUT_TOKENS: '1',
    VIDEO_GENERATION_BASE_URL: 'https://video.example.test',
    VIDEO_GENERATION_API_KEY: 'test-video-api-key',
    VIDEO_GENERATION_MODEL: 'test-video-model',
    VIDEO_GENERATION_CREATE_PATH: '/v1/jobs',
    VIDEO_GENERATION_POLL_PATH: '/v1/jobs/{id}',
    VIDEO_GENERATION_PRICING_VERSION: 'test-pricing-v1',
    VIDEO_GENERATION_UNIT_PRICE_CENTS: '10',
    VIDEO_GENERATION_WEBHOOK_SECRET: 'test-webhook-secret-at-least-32-characters',
    FINANCE_CREDITS_CENTS_POLICY_VERSION: 'test-policy-v1',
    FINANCE_CREDITS_CENTS_POLICY_EFFECTIVE_AT: '2026-08-13T00:00:00Z',
    FINANCE_CREDITS_CENTS_POLICY_ROUNDING: 'HALF_UP',
    FINANCE_CREDITS_CENTS_POLICY_CENTS_NUMERATOR: '1',
    FINANCE_CREDITS_CENTS_POLICY_CREDITS_DENOMINATOR: '1',
    FINANCE_CREDITS_CENTS_POLICY_MAX_CENTS_PER_OPERATION: '100000',
    FINANCE_PSP_MODE: 'custodian-test',
    OTEL_TRACING_SAMPLING_PROBABILITY: '0.1',
    OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: 'https://otel.example.test/v1/traces',
    PRODUCTION_SERVICE_CPU_LIMIT: '2.0',
    PRODUCTION_SERVICE_MEMORY_LIMIT: '2G',
    PRODUCTION_SERVICE_CPU_RESERVATION: '0.25',
    PRODUCTION_SERVICE_MEMORY_RESERVATION: '256M',
    ...overrides,
  }
}

function productionSecretEnvironment(overrides: NodeJS.ProcessEnv = {}): NodeJS.ProcessEnv {
  const root = temporaryDirectory()
  const alertWebhook = join(root, 'alertmanager-webhook-url')
  const grafanaPassword = join(root, 'grafana-admin-password')
  writeFileSync(alertWebhook, 'https://alerts.example.test/receiver', { mode: 0o600 })
  writeFileSync(grafanaPassword, 'test-grafana-admin-password', { mode: 0o600 })
  const env = productionComposeEnvironment({
    SESSION_SECRET: 'test-session-secret-at-least-32-characters',
    IDENTITY_ACCESS_TOKEN_KID: 'access-token-test-v1',
    IDENTITY_ACCESS_TOKEN_SECRET: 'test-access-token-secret-at-least-32-characters',
    EDGE_ACCESS_TOKEN_KID: 'access-token-test-v1',
    EDGE_ACCESS_TOKEN_SECRET: 'test-access-token-secret-at-least-32-characters',
    CRYPTO_KEK_BASE64: Buffer.alloc(32, 7).toString('base64'),
    MINIO_ROOT_PASSWORD: 'test-minio-root-password-at-least-32-characters',
    MINIO_SECRET_KEY: 'test-minio-runtime-secret-at-least-32-characters',
    PUBLIC_FORWARDED_PROTO: 'https',
    SESSION_COOKIE_SECURE: 'always',
    IDENTITY_ASSERTION_REPLAY_ENABLED: 'true',
    IDENTITY_ASSERTION_REPLAY_STORAGE: 'redis',
    CONFIRMATION_WINDOW_SECONDS: '259200',
    KAFKA_SECURITY_PROTOCOL: 'SASL_SSL',
    KAFKA_SASL_MECHANISM: 'SCRAM-SHA-512',
    TEMPORAL_ENABLE_HTTPS: 'true',
    FRONTEND_ORIGIN: 'https://app.example.test',
    PUBLIC_BACKEND_ORIGIN: 'https://api.example.test',
    CORS_ORIGIN: 'https://app.example.test',
    ALERTMANAGER_WEBHOOK_URL_FILE: alertWebhook,
    GRAFANA_ADMIN_PASSWORD_FILE: grafanaPassword,
  })
  const contract = readFileSync(resolve(REPOSITORY_ROOT, 'deploy/security/production-secret-contract.csv'), 'utf8')
  for (const line of contract.split('\n').slice(1).filter(Boolean)) {
    const [name, minimumLength] = line.split(',')
    if (env[name] == null) env[name] = `contract-${name.toLowerCase()}-${'x'.repeat(Number(minimumLength))}`
  }
  const pairs = readFileSync(resolve(REPOSITORY_ROOT, 'deploy/security/identity-assertion-key-pairs.csv'), 'utf8')
  for (const line of pairs.split('\n').slice(1).filter(Boolean)) {
    const [pair, , , , defaultKid] = line.split(',')
    env[`IDENTITY_ASSERTION_KEY_${pair}_KID`] = defaultKid
    env[`IDENTITY_ASSERTION_KEY_${pair}`] = 'test-assertion-secret-at-least-32-characters'
  }
  return { ...env, ...overrides }
}

afterEach(() => {
  for (const directory of temporaryDirectories.splice(0)) {
    rmSync(directory, { recursive: true, force: true })
  }
})

// 全文件为 exec 重型部署契约（validator/compose 渲染）：统一 45s 执行预算。
vi.setConfig({ testTimeout: 45_000 })

describe('Production release and recovery contracts', () => {
  it('authorizes native library access for every Java 25 container', () => {
    const services = [
      'database-bootstrap', 'edge-bff', 'identity-service', 'marketplace-service',
      'finance-service', 'trust-service', 'intelligence-service',
    ]
    for (const service of services) {
      const dockerfile = readFileSync(resolve(
        REPOSITORY_ROOT, `platform-java/services/${service}/Dockerfile`,
      ), 'utf8')
      expect(dockerfile).toContain('"--enable-native-access=ALL-UNNAMED"')
      expect(dockerfile).toContain('"--sun-misc-unsafe-memory-access=allow"')
    }
  })

  it('disables sun.misc.Unsafe in Temporal gRPC shaded Netty on Java 25', () => {
    for (const service of ['marketplace-service', 'trust-service']) {
      const dockerfile = readFileSync(resolve(
        REPOSITORY_ROOT, `platform-java/services/${service}/Dockerfile`,
      ), 'utf8')
      expect(dockerfile).toContain('"-Dio.grpc.netty.shaded.io.netty.noUnsafe=true"')
    }
  })

  it('rejects modifications to migrations already applied in a released environment', () => {
    const output = execFileSync(MIGRATION_VALIDATOR, [], { encoding: 'utf8' })
    const releasedMigrations = readFileSync(MIGRATION_MANIFEST, 'utf8')
      .split('\n').filter(line => /^[0-9a-f]{64} {2}/.test(line))
    expect(output).toContain(`released migration checksums are valid (${releasedMigrations.length} files)`)

    const root = temporaryDirectory()
    const manifest = readFileSync(MIGRATION_MANIFEST, 'utf8')
    for (const line of manifest.split('\n')) {
      if (!/^[0-9a-f]{64} {2}/.test(line)) continue
      const relative = line.slice(66)
      const target = join(root, relative)
      mkdirSync(resolve(target, '..'), { recursive: true })
      writeFileSync(target, readFileSync(resolve(REPOSITORY_ROOT, relative)))
    }
    const v6 = join(root, 'platform-java/services/finance-service/src/main/resources/db/migration/V6__credits_account.sql')
    writeFileSync(v6, `${readFileSync(v6, 'utf8')}\n-- forbidden rewrite\n`)

    const result = spawnSync(MIGRATION_VALIDATOR, ['--root', root], { encoding: 'utf8' })
    expect(result.status).not.toBe(0)
    expect(result.stderr).toContain('released migration was modified')
  })

  it('keeps the local OTLP collector isolated from the production overlay', () => {
    const collectorCompose = readFileSync(OBSERVABILITY_COMPOSE, 'utf8')
    const productionCompose = readFileSync(resolve(REPOSITORY_ROOT, 'docker-compose.production.yml'), 'utf8')
    expect(collectorCompose).toContain('profiles: [observability]')
    expect(collectorCompose).toContain('otel/opentelemetry-collector:0.133.0')
    expect(collectorCompose).toContain('config.yaml:ro')
    expect(productionCompose).not.toContain('otel-collector')
    expect(productionCompose).toContain('OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:?')
    const smoke = readFileSync(LOCAL_OTEL_SMOKE, 'utf8')
    expect(smoke).toContain('/v1/traces')
    expect(smoke).toContain('collector debug exporter did not record the smoke trace')
  })

  it('validates scrape targets, messaging alerts, secret-backed receiver, and dashboards', () => {
    const output = execFileSync(OBSERVABILITY_VALIDATOR, [], { encoding: 'utf8' })
    expect(output).toContain('observability configuration contract is valid')

    const rules = readFileSync(resolve(
      REPOSITORY_ROOT,
      'platform-java/deploy/observability/prometheus/rules/platform-messaging.yml',
    ), 'utf8')
    expect(rules).toContain('GrasslandOutboxMetricsMissing')
    expect(rules).toContain('GrasslandKafkaConsumerLagMetricsMissing')
  })

  it('keeps tracing opt-in and W3C propagation consistent across all Java application services', () => {
    const services = [
      'edge-bff', 'identity-service', 'marketplace-service',
      'finance-service', 'trust-service', 'intelligence-service',
    ]
    for (const service of services) {
      const build = readFileSync(resolve(
        REPOSITORY_ROOT,
        `platform-java/services/${service}/build.gradle.kts`,
      ), 'utf8')
      const application = readFileSync(resolve(
        REPOSITORY_ROOT,
        `platform-java/services/${service}/src/main/resources/application.yml`,
      ), 'utf8')
      expect(build).toContain('implementation(libs.spring.boot.opentelemetry)')
      expect(application).toContain('enabled: ${OTEL_TRACING_ENABLED:false}')
      expect(application).toContain('enabled: ${OTEL_EXPORT_ENABLED:false}')
      expect(application).toContain('probability: ${OTEL_TRACING_SAMPLING_PROBABILITY:0.1}')
      expect(application).toContain('consume: W3C')
      expect(application).toContain('produce: W3C')
      expect(application).toContain('endpoint: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:}')
      expect(application).not.toMatch(/management:\s+tracing:\s+enabled:/)
    }
  })

  it('requires production OTLP export through an explicit non-local HTTPS endpoint', () => {
    const compose = readFileSync(resolve(REPOSITORY_ROOT, 'docker-compose.production.yml'), 'utf8')
    const validator = readFileSync(COMPOSE_VALIDATOR, 'utf8')
    expect(compose).toContain('x-otel-production: &otel-production')
    expect(compose).toContain('OTEL_TRACING_ENABLED: "true"')
    expect(compose).toContain('OTEL_EXPORT_ENABLED: "true"')
    expect(compose).toContain('OTEL_TRACING_SAMPLING_PROBABILITY: ${OTEL_TRACING_SAMPLING_PROBABILITY:?')
    expect(compose).toContain('OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:?')
    expect(validator).toContain('must use an explicit HTTPS OTLP traces endpoint')
    expect(validator).toContain('OTLP traces endpoint must not be local')
  })

  it('renders production OTLP settings and rejects unsafe endpoints or sampling', () => {
    expect(execFileSync(COMPOSE_VALIDATOR, [], {
      env: productionComposeEnvironment(),
      encoding: 'utf8',
    })).toContain('production compose contract is valid')

    for (const endpoint of [
      'http://otel.example.test/v1/traces',
      'https://localhost/v1/traces',
    ]) {
      const result = spawnSync(COMPOSE_VALIDATOR, [], {
        env: productionComposeEnvironment({ OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: endpoint }),
        encoding: 'utf8',
      })
      expect(result.status).toBe(1)
      expect(result.stderr).toContain('OTLP traces endpoint')
    }

    const invalidSampling = spawnSync(COMPOSE_VALIDATOR, [], {
      env: productionComposeEnvironment({ OTEL_TRACING_SAMPLING_PROBABILITY: '1.1' }),
      encoding: 'utf8',
    })
    expect(invalidSampling.status).toBe(1)
    expect(invalidSampling.stderr).toContain('between 0 and 1')
  }, 15_000)

  it('requires the trusted Qwen endpoint and a non-placeholder production key', () => {
    for (const overrides of [
      { QWEN_BASE_URL: 'http://dashscope.aliyuncs.com/compatible-mode/v1' },
      { QWEN_BASE_URL: 'https://provider.example.test/v1' },
      { QWEN_API_KEY: 'replace-with-qwen-api-key' },
    ]) {
      const result = spawnSync(COMPOSE_VALIDATOR, [], {
        env: productionComposeEnvironment(overrides),
        encoding: 'utf8',
      })
      expect(result.status).toBe(1)
      expect(result.stderr).toContain('QWEN_')
    }
  }, 15_000)

  it('requires production CPU and memory limits plus reservations for every application service', () => {
    const compose = readFileSync(resolve(REPOSITORY_ROOT, 'docker-compose.production.yml'), 'utf8')
    const validator = readFileSync(COMPOSE_VALIDATOR, 'utf8')
    expect(compose).toContain('PRODUCTION_SERVICE_CPU_LIMIT')
    expect(compose).toContain('PRODUCTION_SERVICE_MEMORY_LIMIT')
    expect(compose).toContain('PRODUCTION_SERVICE_CPU_RESERVATION')
    expect(compose).toContain('PRODUCTION_SERVICE_MEMORY_RESERVATION')
    expect(validator).toContain('deploy.resources.$kind')
    expect(validator).toContain('database-bootstrap')
    expect(validator).toContain('must define deploy.resources.$kind.memory')
  })

  it('locks the complete domain metric coverage to the services that own outbox or Kafka consumers', () => {
    // 2026-08-20：identity/finance/trust/intelligence 四服务的 OutboxPublisher 下沉到
    // platform-messaging 共享库，backlog 指标单源在共享 publisher；四服务 build 必须依赖该库。
    // marketplace 链为独立实现（String claimToken / fail-fast 校验 / 业务查询），仍读自己的 metrics 类。
    const sharedPublisher = readFileSync(resolve(
      REPOSITORY_ROOT,
      'platform-java/platform-messaging/src/main/java/com/grassland/messaging/outbox/OutboxPublisher.java',
    ), 'utf8')
    expect(sharedPublisher).toContain('grassland.outbox.pending')
    expect(sharedPublisher).toContain('grassland.outbox.oldest.pending.age')
    for (const service of ['identity-service', 'finance-service', 'trust-service', 'intelligence-service']) {
      const build = readFileSync(resolve(
        REPOSITORY_ROOT,
        `platform-java/services/${service}/build.gradle.kts`,
      ), 'utf8')
      expect(build).toContain('project(":platform-messaging")')
    }
    const marketplaceMetrics = readFileSync(resolve(
      REPOSITORY_ROOT,
      'platform-java/services/marketplace-service/src/main/java/com/grassland/marketplace/event/OutboxPublisherMetrics.java',
    ), 'utf8')
    expect(marketplaceMetrics).toContain('grassland.outbox.pending')
    expect(marketplaceMetrics).toContain('grassland.outbox.oldest.pending.age')
    const rules = readFileSync(resolve(REPOSITORY_ROOT, 'platform-java/deploy/observability/prometheus/rules/platform-messaging.yml'), 'utf8')
    expect(rules).toContain('kafka_consumer_records_lag_max{job=~"identity-service|marketplace-service"}')
    expect(rules).toContain('GrasslandKafkaConsumerLagMetricsMissing')
  })

  it('verifies a complete PostgreSQL and MinIO backup manifest', () => {
    const { manifest } = validBackup()
    const output = execFileSync(BACKUP_SCRIPT, ['verify', '--manifest', manifest], {
      encoding: 'utf8',
    })
    expect(output).toContain('manifest verified')
  })

  it('rejects manifests with missing checksums, tampering, or unknown versions', () => {
    const missingChecksum = validBackup()
    writeFileSync(
      missingChecksum.manifest,
      readFileSync(missingChecksum.manifest, 'utf8')
        .replace(/^sha256 .* minio\/grassland\/asset\.bin\n/m, ''),
    )
    expect(spawnSync(BACKUP_SCRIPT, ['verify', '--manifest', missingChecksum.manifest])).toMatchObject({ status: 1 })

    const tampered = validBackup()
    writeFileSync(join(tampered.root, 'postgres.dump'), 'tampered')
    expect(spawnSync(BACKUP_SCRIPT, ['verify', '--manifest', tampered.manifest])).toMatchObject({ status: 1 })

    const unknownVersion = validBackup()
    writeFileSync(
      unknownVersion.manifest,
      readFileSync(unknownVersion.manifest, 'utf8').replace('manifest v1', 'manifest v2'),
    )
    expect(spawnSync(BACKUP_SCRIPT, ['verify', '--manifest', unknownVersion.manifest])).toMatchObject({ status: 1 })
  })

  it('keeps restore dry-run fail-closed to explicitly isolated targets', () => {
    const { manifest } = validBackup()
    const safe = execFileSync(BACKUP_SCRIPT, [
      'restore', '--manifest', manifest,
      '--target-database-url', 'postgresql://user:pass@restore-db.internal/grassland',
      '--target-bucket', 'grassland-restore-drill-contract',
    ], { encoding: 'utf8' })
    expect(safe).toContain('dry-run: restore would target')

    const unsafe = spawnSync(BACKUP_SCRIPT, [
      'restore', '--manifest', manifest,
      '--target-database-url', 'postgresql://user:pass@primary-db.internal/grassland',
      '--target-bucket', 'grassland',
    ], { encoding: 'utf8' })
    expect(unsafe.status).toBe(1)
    expect(unsafe.stderr).toContain('target database host must be localhost')
  })

  it('requires a verified backup for deploy and loads the env file during rollback', () => {
    const release = readFileSync(RELEASE_SCRIPT, 'utf8')
    expect(release).toContain('validate_backup_manifest')
    expect(release).toContain('backup-restore-drill.sh" verify --manifest "$BACKUP_MANIFEST"')
    expect(release).toMatch(/deploy\(\) \{[\s\S]*validate_backup_manifest[\s\S]*compose -f "\$override" up -d --no-build/)
    expect(release).toMatch(/rollback\(\) \{\s+load_env/)
    expect(release.match(/validate_production_compose/g)?.length).toBeGreaterThanOrEqual(5)
    expect(release.match(/validate_observability_config/g)?.length).toBeGreaterThanOrEqual(5)
    expect(release.match(/run_public_smoke/g)?.length).toBe(3)
  })

  it('keeps production smoke read-only and distinguishes optional authenticated coverage', () => {
    const smoke = readFileSync(SMOKE_SCRIPT, 'utf8')
    expect(smoke).toContain('expect_status /health 200')
    expect(smoke).toContain('expect_status /internal 404')
    expect(smoke).toContain('expect_status /api/internal 404')
    expect(smoke).toContain('expect_status /api/__release_smoke_not_found__ 404')
    expect(smoke).toContain('expect_status /api/tasks/feed 401')
    expect(smoke).toContain('expect_status /api/video-production/capabilities 200')
    expect(smoke).toContain('.provider == "seedance" or .provider == "minimax"')
    expect(smoke).toContain('expect_status /api/tasks/feed 200 "$ACCESS_TOKEN"')
    expect(smoke).not.toMatch(/--request\s+(POST|PUT|PATCH|DELETE)|-X\s*(POST|PUT|PATCH|DELETE)/)
  })

  it('builds every application image, blocks high vulnerabilities, and emits SPDX SBOM evidence', () => {
    const imageSecurity = readFileSync(IMAGE_SECURITY_SCRIPT, 'utf8')
    const workflow = readFileSync(resolve(REPOSITORY_ROOT, '.github/workflows/ci.yml'), 'utf8')
    expect(imageSecurity).toContain('SERVICES=(frontend database-bootstrap edge-bff identity-service marketplace-service finance-service trust-service intelligence-service)')
    expect(imageSecurity).toContain('--severity HIGH,CRITICAL --exit-code 1')
    expect(imageSecurity).not.toContain('--ignore-unfixed')
    expect(imageSecurity).toContain('--format spdx-json')
    expect(imageSecurity).toContain('aquasec/trivy:0.69.3')
    expect(imageSecurity).toContain('signing-status.txt')
    expect(imageSecurity).toContain('IMAGE_SECURITY_PULL="${IMAGE_SECURITY_PULL:-true}"')
    expect(imageSecurity).toContain('base_image_pull=%s')
    expect(imageSecurity).toContain('docker build --pull')
    expect(imageSecurity).toContain('TRIVY_CACHE_VOLUME')
    expect(imageSecurity).toContain('--cache-dir /root/.cache/trivy')
    expect(workflow).toContain('image-security:')
    expect(workflow).toContain('scripts/ci-image-security.sh')
    expect(workflow).toContain('scripts/validate-image-security-evidence.sh')
    expect(workflow).toContain('docker/setup-buildx-action@v3')
    expect(workflow).toContain('docker buildx inspect --bootstrap')
    expect(imageSecurity).toContain('Docker daemon is unavailable')
    expect(imageSecurity).toContain('Docker Buildx is required')
    expect(workflow).toContain('test-artifacts/image-security/')
  })

  it('fails CI when any image vulnerability or SPDX evidence is missing or malformed', () => {
    const evidence = readFileSync(IMAGE_EVIDENCE_SCRIPT, 'utf8')
    expect(evidence).toContain('missing vulnerability report')
    expect(evidence).toContain('missing SPDX SBOM')
    expect(evidence).toContain('.Results | type')
    expect(evidence).toContain('.spdxVersion')
    expect(evidence).toContain('signing-status.txt')
  })

  it('validates complete image evidence and rejects missing or malformed reports', () => {
    const root = temporaryDirectory()
    const services = [
      'frontend', 'database-bootstrap', 'edge-bff', 'identity-service',
      'marketplace-service', 'finance-service', 'trust-service', 'intelligence-service',
    ]
    for (const service of services) {
      writeFileSync(join(root, `${service}.vulnerabilities.json`), '{"Results":[]}')
      writeFileSync(join(root, `${service}.spdx.json`), '{"spdxVersion":"SPDX-2.3"}')
    }
    writeFileSync(join(root, 'signing-status.txt'), 'deferred until immutable registry digest')
    expect(execFileSync(IMAGE_EVIDENCE_SCRIPT, [root], { encoding: 'utf8' })).toContain('8 services')

    rmSync(join(root, 'finance-service.spdx.json'))
    expect(spawnSync(IMAGE_EVIDENCE_SCRIPT, [root], { encoding: 'utf8' }).status).toBe(1)

    writeFileSync(join(root, 'finance-service.spdx.json'), '{"spdxVersion":42}')
    expect(spawnSync(IMAGE_EVIDENCE_SCRIPT, [root], { encoding: 'utf8' }).status).toBe(1)
  })

  it('renders a non-mutating canary plan with measured abort gates and rollback evidence', () => {
    const output = execFileSync(CANARY_PLAN_SCRIPT, [
      'plan',
      '--release-id', 'contract-canary-01',
      '--canary-image', 'registry.example/grassland/edge@sha256:' + 'a'.repeat(64),
      '--baseline-image', 'registry.example/grassland/edge@sha256:' + 'b'.repeat(64),
      '--public-health-url', 'https://api.example.test/health',
      '--prometheus-url', 'https://prometheus.example.test',
    ], { encoding: 'utf8' })
    expect(output).toContain('mode=dry-run')
    expect(output).toContain('1% of traffic')
    expect(output).toContain('p95 latency > 2 seconds')
    expect(output).toContain('5xx ratio > 5%')
    expect(output).toContain('No traffic was changed by this command.')
    expect(output).toContain('production-smoke.sh')
  })

  it('rejects canary plans without digest images or HTTPS observability endpoints', () => {
    const args = [
      'plan', '--release-id', 'contract-canary-01',
      '--canary-image', 'registry.example/grassland/edge:latest',
      '--baseline-image', 'registry.example/grassland/edge@sha256:' + 'b'.repeat(64),
      '--public-health-url', 'http://api.example.test/health',
      '--prometheus-url', 'https://prometheus.example.test',
    ]
    expect(spawnSync(CANARY_PLAN_SCRIPT, args, { encoding: 'utf8' }).status).toBe(1)
  })

  it('requires cosign signatures and SPDX attestations for deploy and rollback images', () => {
    const provenance = readFileSync(PROVENANCE_SCRIPT, 'utf8')
    const release = readFileSync(RELEASE_SCRIPT, 'utf8')
    expect(provenance).toContain('COSIGN_PUBLIC_KEY_FILE must point to a readable deployment public key')
    expect(provenance).toContain('verify --key "$COSIGN_PUBLIC_KEY_FILE" "$image"')
    expect(provenance).toContain('verify-attestation --key "$COSIGN_PUBLIC_KEY_FILE" --type spdxjson "$image"')
    expect(provenance).toContain('previous-images.tsv')
    expect(release.match(/validate_image_provenance/g)?.length).toBe(5)
  })

  it('executes cosign signature and SPDX attestation verification for every release service', () => {
    const root = temporaryDirectory()
    const fakeCosign = join(root, 'cosign')
    const log = join(root, 'cosign.log')
    const key = join(root, 'cosign.pub')
    writeFileSync(key, 'test-public-key')
    writeFileSync(fakeCosign, '#!/bin/sh\nprintf "%s\\n" "$*" >> "$COSIGN_LOG"\n')
    chmodSync(fakeCosign, 0o700)

    const env: NodeJS.ProcessEnv = {
      ...process.env,
      COSIGN_BIN: fakeCosign,
      COSIGN_PUBLIC_KEY_FILE: key,
      COSIGN_LOG: log,
    }
    for (const service of [
      'frontend', 'database-bootstrap', 'edge-bff', 'identity-service',
      'marketplace-service', 'finance-service', 'trust-service', 'intelligence-service',
    ]) {
      env[`RELEASE_IMAGE_${service.toUpperCase().replace(/-/g, '_')}`] = `registry.example/${service}@sha256:${'a'.repeat(64)}`
    }

    execFileSync(PROVENANCE_SCRIPT, { env, encoding: 'utf8' })
    const calls = readFileSync(log, 'utf8').trim().split('\n')
    expect(calls).toHaveLength(16)
    expect(calls.filter((call) => call.startsWith('verify --key'))).toHaveLength(8)
    expect(calls.filter((call) => call.startsWith('verify-attestation --key'))).toHaveLength(8)
  })

  it('fails closed when any image attestation verification fails', () => {
    const root = temporaryDirectory()
    const fakeCosign = join(root, 'cosign')
    const key = join(root, 'cosign.pub')
    writeFileSync(key, 'test-public-key')
    writeFileSync(fakeCosign, [
      '#!/bin/sh',
      '[ "$1" != "verify-attestation" ]',
      '',
    ].join('\n'))
    chmodSync(fakeCosign, 0o700)

    const env: NodeJS.ProcessEnv = {
      ...process.env,
      COSIGN_BIN: fakeCosign,
      COSIGN_PUBLIC_KEY_FILE: key,
    }
    for (const service of [
      'frontend', 'database-bootstrap', 'edge-bff', 'identity-service',
      'marketplace-service', 'finance-service', 'trust-service', 'intelligence-service',
    ]) {
      env[`RELEASE_IMAGE_${service.toUpperCase().replace(/-/g, '_')}`] = `registry.example/${service}@sha256:${'a'.repeat(64)}`
    }

    expect(spawnSync(PROVENANCE_SCRIPT, { env, encoding: 'utf8' }).status).toBe(1)
  })

  it('locks production Compose to external TLS dependencies and readiness gates', () => {
    const validator = readFileSync(COMPOSE_VALIDATOR, 'utf8')
    expect(validator).toContain('production compose must not include local service')
    expect(validator).toContain('KAFKA_SECURITY_PROTOCOL')
    expect(validator).toContain('KAFKA_SSL_TRUSTSTORE_LOCATION')
    expect(validator).toContain('SPRING_TEMPORAL_CONNECTION_MTLS_INSECURE_TRUST_MANAGER')
    expect(validator).toContain('/actuator/health/readiness')
    expect(validator).toContain('condition == "service_healthy"')
  })

  it('fails closed unless production AI capabilities use configured real providers', () => {
    const overlay = readFileSync(resolve(REPOSITORY_ROOT, 'docker-compose.production.yml'), 'utf8')
    const validator = readFileSync(COMPOSE_VALIDATOR, 'utf8')
    const secrets = readFileSync(SECRET_VALIDATOR, 'utf8')
    const contract = readFileSync(resolve(
      REPOSITORY_ROOT,
      'deploy/security/production-secret-contract.csv',
    ), 'utf8')
    const required = [
      'VIDEO_GENERATION_MODE',
      'VIDEO_GENERATION_BASE_URL',
      'VIDEO_GENERATION_API_KEY',
      'VIDEO_GENERATION_MODEL',
      'VIDEO_GENERATION_CREATE_PATH',
      'VIDEO_GENERATION_POLL_PATH',
      'VIDEO_GENERATION_PRICING_VERSION',
      'VIDEO_GENERATION_UNIT_PRICE_CENTS',
      'VIDEO_GENERATION_WEBHOOK_SECRET',
      'AI_SPEECH_PROVIDER',
      'AI_SPEECH_BASE_URL',
      'AI_SPEECH_API_KEY',
      'AI_SPEECH_MODEL',
      'AI_SPEECH_TRANSCRIPTION_PATH',
      'AI_SPEECH_CENTS_PER_SECOND',
      'AI_EMBEDDING_PROVIDER',
      'AI_EMBEDDING_BASE_URL',
      'AI_EMBEDDING_API_KEY',
      'AI_EMBEDDING_MODEL',
      'AI_EMBEDDING_PATH',
      'AI_EMBEDDING_DIMENSIONS',
      'AI_EMBEDDING_CENTS_PER_1K_INPUT_TOKENS',
      'FINANCE_CREDITS_CENTS_POLICY_VERSION',
      'FINANCE_CREDITS_CENTS_POLICY_EFFECTIVE_AT',
      'FINANCE_CREDITS_CENTS_POLICY_ROUNDING',
      'FINANCE_CREDITS_CENTS_POLICY_CENTS_NUMERATOR',
      'FINANCE_CREDITS_CENTS_POLICY_CREDITS_DENOMINATOR',
      'FINANCE_CREDITS_CENTS_POLICY_MAX_CENTS_PER_OPERATION',
    ]
    for (const name of required) {
      expect(overlay).toContain(`${name}: \${${name}:?`)
      expect(validator).toContain(name)
    }
    expect(secrets).toContain('VIDEO_GENERATION_MODE must be seedance or minimax in production')
    expect(secrets).toContain('VIDEO_GENERATION_BASE_URL must use https in production')
    expect(secrets).toContain('FINANCE_PSP_MODE must select a real production adapter')
    expect(contract).toContain('VIDEO_GENERATION_API_KEY,16,true,intelligence')
    expect(contract).toContain('VIDEO_GENERATION_WEBHOOK_SECRET,32,true,intelligence')
    expect(contract).toContain('AI_SPEECH_API_KEY,16,true,intelligence')
    expect(contract).toContain('AI_EMBEDDING_API_KEY,16,true,intelligence')
    expect(overlay).toContain('AI_PROVIDER_ALLOW_SANDBOX: "false"')
    expect(validator).toContain('must disable Sandbox AI providers in production')
  })

  it('fails closed unless production Finance selects a non-Sandbox PSP adapter', () => {
    const overlay = readFileSync(resolve(REPOSITORY_ROOT, 'docker-compose.production.yml'), 'utf8')
    const validator = readFileSync(COMPOSE_VALIDATOR, 'utf8')
    expect(overlay).toContain('FINANCE_PSP_MODE: ${FINANCE_PSP_MODE:?')
    expect(validator).toContain('FINANCE_PSP_MODE')
    expect(validator).toContain('production PSP adapter must not be Sandbox')

    const valid = execFileSync(COMPOSE_VALIDATOR, [], {
      env: productionComposeEnvironment(),
      encoding: 'utf8',
    })
    expect(valid).toContain('production compose contract is valid')

    const sandbox = spawnSync(COMPOSE_VALIDATOR, [], {
      env: productionComposeEnvironment({ FINANCE_PSP_MODE: 'sandbox' }),
      encoding: 'utf8',
    })
    expect(sandbox.status).toBe(1)
    expect(sandbox.stderr).toContain('production PSP adapter must not be Sandbox')

    const secretValidator = readFileSync(SECRET_VALIDATOR, 'utf8')
    expect(secretValidator).toContain('FINANCE_PSP_MODE is required in production')
    expect(secretValidator).toContain('FINANCE_PSP_MODE must select a real production adapter')
  })

  it.each([
    'kafka-unavailable',
    'temporal-unavailable',
    'minio-unavailable',
    'video-provider-unavailable',
    'readiness-failure',
  ])('renders a non-mutating %s drill plan with recovery evidence', (scenario) => {
    const output = execFileSync(FAILURE_DRILL_SCRIPT, [
      'plan', '--scenario', scenario, '--drill-id', `contract-${scenario}`,
    ], { encoding: 'utf8' })
    expect(output).toContain(`scenario=${scenario}`)
    expect(output).toContain('mode=dry-run')
    expect(output).toContain('Injection:')
    expect(output).toContain('Recover:')
    expect(output).toContain('Abort condition:')
    expect(output).toContain('Evidence required:')
    expect(output).toContain('No fault was injected by this command.')
  })

  it('renders a non-mutating public video webhook drill and gates live execution', () => {
    const args = [
      '--base-url', 'https://api.example.test', '--provider', 'minimax',
      '--provider-task-id', 'provider-task-contract',
      '--job-id', '11111111-1111-1111-1111-111111111111',
      '--drill-id', 'contract-video-webhook',
    ]
    const plan = execFileSync(VIDEO_WEBHOOK_DRILL_SCRIPT, ['plan', ...args], { encoding: 'utf8' })
    expect(plan).toContain('mode=dry-run')
    expect(plan).toContain('same-event replay')
    expect(plan).toContain('status=processing and progress=80')
    expect(plan).toContain('No webhook was sent by this plan.')

    const live = spawnSync(VIDEO_WEBHOOK_DRILL_SCRIPT, ['run', ...args], { encoding: 'utf8' })
    expect(live.status).toBe(1)
    expect(live.stderr).toContain('run requires --confirm-live-webhook-drill')
  })

  it('executes the webhook drill with valid HMAC, replay, stale progress, and redacted evidence', () => {
    const root = temporaryDirectory()
    const bin = join(root, 'bin')
    mkdirSync(bin)
    const mockCurl = join(bin, 'curl')
    writeFileSync(mockCurl, `#!/usr/bin/env node
const { createHmac } = require('node:crypto')
const { existsSync, readFileSync, writeFileSync } = require('node:fs')
const args = process.argv.slice(2)
const value = (name) => args[args.indexOf(name) + 1]
const output = value('--output')
const headerArg = value('--header')
const url = args.at(-1)
const stateFile = process.env.WEBHOOK_DRILL_TEST_STATE
const state = existsSync(stateFile) ? JSON.parse(readFileSync(stateFile, 'utf8')) : { progress: 0, events: {} }
let status = 500
if (url.includes('/webhooks/')) {
  const headers = readFileSync(headerArg.slice(1), 'utf8')
  const header = (name) => headers.match(new RegExp('^' + name + ': (.+)$', 'mi'))?.[1].trim()
  const event = header('X-Video-Event-Id')
  const timestamp = header('X-Video-Timestamp')
  const signature = header('X-Video-Signature')
  const body = value('--data-binary')
  const expected = createHmac('sha256', process.env.WEBHOOK_DRILL_TEST_SECRET)
    .update(timestamp + '.' + event + '.' + body).digest('hex')
  if (signature !== expected) status = 401
  else if (state.events[event] && state.events[event] !== body) status = 409
  else {
    state.events[event] = body
    state.progress = Math.max(state.progress, JSON.parse(body).progress)
    status = 200
  }
  writeFileSync(stateFile, JSON.stringify(state))
  writeFileSync(output, status === 200 ? '{"success":true}' : '{"error":"rejected"}')
} else {
  const headers = readFileSync(headerArg.slice(1), 'utf8')
  status = headers.trim() === 'Authorization: Bearer drill-owner-token' ? 200 : 401
  writeFileSync(output, JSON.stringify({ success: true, data: { status: 'processing', progress: state.progress } }))
}
process.stdout.write(String(status))
`)
    chmodSync(mockCurl, 0o755)
    const secret = join(root, 'webhook-secret')
    const token = join(root, 'access-token')
    const evidence = join(root, 'evidence.txt')
    const state = join(root, 'state.json')
    writeFileSync(secret, 'contract-webhook-secret-at-least-32-characters\n', { mode: 0o600 })
    writeFileSync(token, 'drill-owner-token\n', { mode: 0o600 })

    const output = execFileSync(VIDEO_WEBHOOK_DRILL_SCRIPT, [
      'run', '--base-url', 'http://127.0.0.1:18080', '--allow-http',
      '--provider', 'minimax', '--provider-task-id', 'provider-task-contract',
      '--job-id', '11111111-1111-1111-1111-111111111111',
      '--drill-id', 'contract-live-video-webhook', '--access-token-file', token,
      '--secret-file', secret, '--output', evidence, '--confirm-live-webhook-drill',
    ], {
      encoding: 'utf8',
      env: {
        ...process.env,
        PATH: `${bin}:${process.env.PATH}`,
        WEBHOOK_DRILL_TEST_SECRET: 'contract-webhook-secret-at-least-32-characters',
        WEBHOOK_DRILL_TEST_STATE: state,
      },
    })
    expect(output).toContain('live webhook drill passed')
    const recorded = readFileSync(evidence, 'utf8')
    expect(recorded).toContain('same_event_replay_http=200')
    expect(recorded).toContain('stale_progress_20_http=200')
    expect(recorded).toContain('invalid_signature_http=401')
    expect(recorded).toContain('final_job_progress=80')
    expect(recorded).toContain('secrets_recorded=false')
    expect(recorded).not.toContain('drill-owner-token')
    expect(recorded).not.toContain('contract-webhook-secret')
  })

  it('plans archive/Finance authority reconciliation and gates live execution', () => {
    const args = [
      '--base-url', 'https://api.example.test',
      '--job-id', '11111111-1111-1111-1111-111111111111',
      '--drill-id', 'contract-archive-reconciliation',
    ]
    const plan = execFileSync(VIDEO_ARCHIVE_DRILL_SCRIPT, ['plan', ...args], { encoding: 'utf8' })
    expect(plan).toContain('mode=dry-run')
    expect(plan).toContain('Finance authority reports consumed')
    expect(plan).toContain('monetaryConversionState=policy_missing')
    expect(plan).toContain('No request was sent by this plan.')
    const live = spawnSync(VIDEO_ARCHIVE_DRILL_SCRIPT, ['run', ...args], { encoding: 'utf8' })
    expect(live.status).toBe(1)
    expect(live.stderr).toContain('run requires --confirm-live-archive-drill')
  })

  it('executes archive and Finance authority reconciliation with redacted object evidence', () => {
    const root = temporaryDirectory()
    const bin = join(root, 'bin')
    mkdirSync(bin)
    const mockCurl = join(bin, 'curl')
    writeFileSync(mockCurl, `#!/usr/bin/env node
const { readFileSync, writeFileSync } = require('node:fs')
const args = process.argv.slice(2)
const value = (name) => args[args.indexOf(name) + 1]
const output = value('--output')
let url = args.at(-1)
if (args.includes('--config')) {
  url = readFileSync(value('--config'), 'utf8').match(/url = "(.+)"/)[1]
}
let status = 200
if (url.includes('/video-reconciliation')) {
  const auth = readFileSync(value('--header').slice(1), 'utf8').trim()
  status = auth === 'Authorization: Bearer archive-admin-token' ? 200 : 401
  writeFileSync(output, JSON.stringify({ monetaryConversionState: 'policy_missing', items: [{
    jobId: '11111111-1111-1111-1111-111111111111', reconciliationState: 'consistent',
    financeAuthorityState: 'consumed', monetaryConversionState: 'policy_missing', issues: [],
  }] }))
} else if (url.includes('/download-url')) {
  const auth = readFileSync(value('--header').slice(1), 'utf8').trim()
  status = auth === 'Authorization: Bearer archive-owner-token' ? 200 : 401
  writeFileSync(output, JSON.stringify({ downloadUrl: 'http://object.example.test/signed?secret=hidden' }))
} else {
  writeFileSync(output, Buffer.from('real-video-object'))
}
process.stdout.write(String(status))
`)
    chmodSync(mockCurl, 0o755)
    const owner = join(root, 'owner-token')
    const admin = join(root, 'admin-token')
    const evidence = join(root, 'evidence.txt')
    writeFileSync(owner, 'archive-owner-token\n', { mode: 0o600 })
    writeFileSync(admin, 'archive-admin-token\n', { mode: 0o600 })
    const output = execFileSync(VIDEO_ARCHIVE_DRILL_SCRIPT, [
      'run', '--base-url', 'http://127.0.0.1:18081', '--allow-http',
      '--job-id', '11111111-1111-1111-1111-111111111111',
      '--drill-id', 'contract-live-archive', '--owner-token-file', owner,
      '--admin-token-file', admin, '--output', evidence,
      '--confirm-live-archive-drill', '--ack-monetary-policy-missing',
    ], { encoding: 'utf8', env: { ...process.env, PATH: `${bin}:${process.env.PATH}` } })
    expect(output).toContain('archive and authority reconciliation passed')
    const recorded = readFileSync(evidence, 'utf8')
    expect(recorded).toContain('finance_authority_state=consumed')
    expect(recorded).toContain('monetary_conversion_state=policy_missing')
    expect(recorded).toContain('object_size_bytes=17')
    expect(recorded).toMatch(/object_sha256=[a-f0-9]{64}/)
    expect(recorded).toContain('signed_url_recorded=false')
    expect(recorded).not.toContain('archive-owner-token')
    expect(recorded).not.toContain('archive-admin-token')
    expect(recorded).not.toContain('secret=hidden')
  })

  it('promotes only a fresh, linked, policy-complete video evidence bundle', () => {
    const root = temporaryDirectory()
    const webhook = join(root, 'webhook.txt')
    const archive = join(root, 'archive.txt')
    const now = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z')
    const jobId = '11111111-1111-1111-1111-111111111111'
    writeFileSync(webhook, [
      '# Grassland video webhook drill evidence v1',
      'drill_id=promotion-webhook',
      'provider=minimax',
      'provider_task_id=provider-task-contract',
      `job_id=${jobId}`,
      `started_at=${now}`,
      `ended_at=${now}`,
      'progress_80_http=200',
      'same_event_replay_http=200',
      'stale_progress_20_http=200',
      'invalid_signature_http=401',
      'final_job_status=processing',
      'final_job_progress=80',
      'result=passed',
      'secrets_recorded=false',
      '',
    ].join('\n'))
    writeFileSync(archive, [
      '# Grassland video archive and reconciliation evidence v1',
      'drill_id=promotion-archive',
      `job_id=${jobId}`,
      `started_at=${now}`,
      `ended_at=${now}`,
      'reconciliation_http=200',
      'finance_authority_state=consumed',
      'local_reconciliation_state=consistent',
      'monetary_conversion_state=credits-cents-v1',
      'download_url_http=200',
      'object_download_http=200',
      'object_size_bytes=17',
      `object_sha256=${'a'.repeat(64)}`,
      'result=passed',
      'secrets_recorded=false',
      'signed_url_recorded=false',
      '',
    ].join('\n'))
    const args = [
      '--webhook-evidence', webhook, '--archive-evidence', archive,
      '--job-id', jobId, '--provider', 'minimax', '--policy-version', 'credits-cents-v1',
    ]
    expect(execFileSync(VIDEO_EVIDENCE_VALIDATOR, args, { encoding: 'utf8' }))
      .toContain('bundle is valid for promotion')

    writeFileSync(archive, readFileSync(archive, 'utf8').replace(`job_id=${jobId}`, 'job_id=22222222-2222-2222-2222-222222222222'))
    expect(spawnSync(VIDEO_EVIDENCE_VALIDATOR, args, { encoding: 'utf8' }).status).toBe(1)
    writeFileSync(archive, readFileSync(archive, 'utf8').replace('job_id=22222222-2222-2222-2222-222222222222', `job_id=${jobId}`)
      .replace('monetary_conversion_state=credits-cents-v1', 'monetary_conversion_state=policy_missing'))
    expect(spawnSync(VIDEO_EVIDENCE_VALIDATOR, args, { encoding: 'utf8' }).status).toBe(1)
    writeFileSync(archive, readFileSync(archive, 'utf8').replace('monetary_conversion_state=policy_missing', 'monetary_conversion_state=credits-cents-v1')
      .replace(`ended_at=${now}`, 'ended_at=2020-01-01T00:00:00Z'))
    expect(spawnSync(VIDEO_EVIDENCE_VALIDATOR, args, { encoding: 'utf8' }).status).toBe(1)
    writeFileSync(archive, readFileSync(archive, 'utf8').replace('ended_at=2020-01-01T00:00:00Z', `ended_at=${now}`)
      .replace('signed_url_recorded=false', 'signed_url_recorded=false\nsigned_url_recorded=false'))
    expect(spawnSync(VIDEO_EVIDENCE_VALIDATOR, args, { encoding: 'utf8' }).status).toBe(1)
  })

  it('exposes an explicit production-release promote command for video evidence', () => {
    const release = readFileSync(RELEASE_SCRIPT, 'utf8')
    expect(release).toContain('promote --release-id ID')
    expect(release).toContain('validate-video-production-evidence.sh')
    expect(release).toMatch(/promote\(\) \{[\s\S]*validate_video_evidence[\s\S]*promotion gate passed/)
  })

  it('promotes only fresh canary evidence with all hold points and safe metrics', () => {
    const root = temporaryDirectory()
    const evidence = join(root, 'canary.txt')
    const now = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z')
    const canary = 'registry.example/grassland/edge-bff@sha256:' + 'a'.repeat(64)
    const baseline = 'registry.example/grassland/edge-bff@sha256:' + 'b'.repeat(64)
    writeFileSync(evidence, [
      '# Grassland production canary evidence v1',
      'evidence_id=canary-contract-01',
      'release_id=2026-08-13.1',
      'service=edge-bff',
      `canary_image=${canary}`,
      `baseline_image=${baseline}`,
      `started_at=${now}`,
      `ended_at=${now}`,
      'warmup_status=passed', 'phase_1_status=passed', 'phase_10_status=passed',
      'phase_25_status=passed', 'phase_50_status=passed', 'phase_100_status=passed',
      'warmup_minutes=5', 'phase_1_minutes=10', 'phase_10_minutes=10',
      'phase_25_minutes=10', 'phase_50_minutes=10', 'phase_100_minutes=10',
      'canary_5xx_pct=1.2', 'baseline_5xx_pct=1.0', 'canary_p95_ms=1200', 'baseline_p95_ms=1000',
      'readiness_status=passed', 'dependency_status=passed', 'finance_reconciliation_status=passed',
      'alert_delivery_status=passed', 'smoke_status=passed', 'rollback_ready=passed',
      'result=passed', 'secrets_recorded=false', '',
    ].join('\n'))
    const args = ['--evidence', evidence, '--release-id', '2026-08-13.1', '--service', 'edge-bff', '--canary-image', canary, '--baseline-image', baseline]
    expect(execFileSync(CANARY_EVIDENCE_VALIDATOR, args, { encoding: 'utf8' }))
      .toContain('valid for promotion')
    writeFileSync(evidence, readFileSync(evidence, 'utf8').replace('canary_5xx_pct=1.2', 'canary_5xx_pct=6'))
    expect(spawnSync(CANARY_EVIDENCE_VALIDATOR, args, { encoding: 'utf8' }).status).toBe(1)
    writeFileSync(evidence, readFileSync(evidence, 'utf8').replace('canary_5xx_pct=6', 'canary_5xx_pct=1.2').replace(`ended_at=${now}`, 'ended_at=2020-01-01T00:00:00Z'))
    expect(spawnSync(CANARY_EVIDENCE_VALIDATOR, args, { encoding: 'utf8' }).status).toBe(1)
    writeFileSync(evidence, readFileSync(evidence, 'utf8').replace('ended_at=2020-01-01T00:00:00Z', `ended_at=${now}`).replace(`canary_image=${canary}`, `canary_image=${baseline}`))
    expect(spawnSync(CANARY_EVIDENCE_VALIDATOR, args, { encoding: 'utf8' }).status).toBe(1)
    writeFileSync(evidence, readFileSync(evidence, 'utf8').replace(`canary_image=${baseline}`, `canary_image=${canary}`).replace('result=passed', 'result=passed\nresult=passed'))
    expect(spawnSync(CANARY_EVIDENCE_VALIDATOR, args, { encoding: 'utf8' }).status).toBe(1)
    const release = readFileSync(RELEASE_SCRIPT, 'utf8')
    expect(release).toContain('canary-promote --release-id ID')
    expect(release).toContain('validate-production-canary-evidence.sh')
  })

  it('promotes only measured fault and recovery evidence with alert and RTO/RPO results', () => {
    const root = temporaryDirectory()
    const evidence = join(root, 'failure.txt')
    const now = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z')
    writeFileSync(evidence, [
      '# Grassland production failure evidence v1',
      'evidence_id=failure-contract-01',
      'release_id=2026-08-13.1',
      'scenario=readiness-failure',
      'dependency=edge-bff-instance-1',
      `started_at=${now}`, `fault_injected_at=${now}`, `recovered_at=${now}`, `ended_at=${now}`,
      'injection_status=passed', 'recovery_status=passed', 'alert_delivery_status=passed',
      'readiness_status=passed', 'smoke_status=passed', 'data_consistency_status=passed',
      'finance_reconciliation_status=passed', 'rto_seconds=42', 'rpo_seconds=0',
      'rollback_ready=passed', 'result=passed', 'secrets_recorded=false', '',
    ].join('\n'))
    const args = ['--evidence', evidence, '--release-id', '2026-08-13.1', '--scenario', 'readiness-failure']
    expect(execFileSync(FAILURE_EVIDENCE_VALIDATOR, args, { encoding: 'utf8' }))
      .toContain('valid for promotion')
    writeFileSync(evidence, readFileSync(evidence, 'utf8').replace('alert_delivery_status=passed', 'alert_delivery_status=missing'))
    expect(spawnSync(FAILURE_EVIDENCE_VALIDATOR, args, { encoding: 'utf8' }).status).toBe(1)
    writeFileSync(evidence, readFileSync(evidence, 'utf8').replace('alert_delivery_status=missing', 'alert_delivery_status=passed').replace('rto_seconds=42', 'rto_seconds=-1'))
    expect(spawnSync(FAILURE_EVIDENCE_VALIDATOR, args, { encoding: 'utf8' }).status).toBe(1)
    writeFileSync(evidence, readFileSync(evidence, 'utf8').replace('rto_seconds=-1', 'rto_seconds=42').replace('scenario=readiness-failure', 'scenario=kafka-unavailable'))
    expect(spawnSync(FAILURE_EVIDENCE_VALIDATOR, args, { encoding: 'utf8' }).status).toBe(1)
    expect(readFileSync(RELEASE_SCRIPT, 'utf8')).toContain('failure-promote --release-id ID')
    expect(readFileSync(RELEASE_SCRIPT, 'utf8')).toContain('validate-production-failure-evidence.sh')
  })

  it('promotes only measured alert firing, receiver delivery, and resolution evidence', () => {
    const root = temporaryDirectory()
    const evidence = join(root, 'observability.txt')
    const now = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z')
    writeFileSync(evidence, [
      '# Grassland observability evidence v1',
      'evidence_id=alert-contract-01',
      'release_id=2026-08-13.1',
      'alert_name=GrasslandReleaseDeliveryTest',
      `started_at=${now}`, `fired_at=${now}`, `delivered_at=${now}`, `resolved_at=${now}`, `ended_at=${now}`,
      'prometheus_query_status=passed', 'alertmanager_status=passed', 'receiver_status=passed',
      'delivery_status=passed', 'resolved_status=passed', 'targets_status=passed', 'smoke_status=passed',
      'secrets_recorded=false', 'result=passed', '',
    ].join('\n'))
    const args = ['--evidence', evidence, '--release-id', '2026-08-13.1']
    expect(execFileSync(OBSERVABILITY_EVIDENCE_VALIDATOR, args, { encoding: 'utf8' }))
      .toContain('valid for promotion')
    writeFileSync(evidence, readFileSync(evidence, 'utf8').replace('receiver_status=passed', 'receiver_status=missing'))
    expect(spawnSync(OBSERVABILITY_EVIDENCE_VALIDATOR, args, { encoding: 'utf8' }).status).toBe(1)
    writeFileSync(evidence, readFileSync(evidence, 'utf8').replace('receiver_status=missing', 'receiver_status=passed').replace('release_id=2026-08-13.1', 'release_id=other'))
    expect(spawnSync(OBSERVABILITY_EVIDENCE_VALIDATOR, args, { encoding: 'utf8' }).status).toBe(1)
    const release = readFileSync(RELEASE_SCRIPT, 'utf8')
    expect(release).toContain('observability-promote --release-id ID')
    expect(release).toContain('validate-observability-evidence.sh')
  })

  it('promotes only ordered three-phase identity key rotation evidence', () => {
    const root = temporaryDirectory()
    const evidence = join(root, 'rotation.txt')
    const base = new Date(Date.now() - 120_000)
    const stamp = (offset: number) => new Date(base.getTime() + offset * 1000).toISOString().replace(/\.\d{3}Z$/, 'Z')
    writeFileSync(evidence, [
      '# Grassland identity key rotation evidence v1',
      'evidence_id=rotation-contract-01', 'release_id=2026-08-13.1',
      'rotation_kind=access-token', 'target=edge-bff', 'old_kid=access-token-old', 'new_kid=access-token-new',
      `phase_1_started_at=${stamp(0)}`, `phase_1_completed_at=${stamp(5)}`,
      `phase_2_started_at=${stamp(6)}`, `phase_2_completed_at=${stamp(10)}`,
      `phase_3_started_at=${stamp(81)}`, `phase_3_completed_at=${stamp(85)}`,
      'ttl_seconds=60', 'leeway_seconds=10', 'phase_1_status=passed', 'phase_2_status=passed',
      'phase_3_status=passed', 'validation_status=passed', 'rollback_status=passed',
      'old_key_retired=true', 'secrets_recorded=false', 'result=passed', '',
    ].join('\n'))
    const args = ['--evidence', evidence, '--release-id', '2026-08-13.1']
    expect(execFileSync(ROTATION_EVIDENCE_VALIDATOR, args, { encoding: 'utf8' }))
      .toContain('valid for promotion')
    writeFileSync(evidence, readFileSync(evidence, 'utf8').replace('phase_3_started_at=', 'phase_3_started_at=2020-01-01T00:00:00Z\n# phase_3_started_at='))
    expect(spawnSync(ROTATION_EVIDENCE_VALIDATOR, args, { encoding: 'utf8' }).status).toBe(1)
    const release = readFileSync(RELEASE_SCRIPT, 'utf8')
    expect(release).toContain('key-rotation-promote --release-id ID')
    expect(release).toContain('validate-identity-key-rotation-evidence.sh')
  })

  it('promotes historical credentials only after rollout, rollback, revocation, rejection, and audit evidence', () => {
    const root = temporaryDirectory()
    const evidence = join(root, 'credential-rotation.txt')
    const base = new Date(Date.now() - 120_000)
    const stamp = (offset: number) => new Date(base.getTime() + offset * 1000).toISOString().replace(/\.\d{3}Z$/, 'Z')
    writeFileSync(evidence, [
      '# Grassland credential rotation evidence v1',
      'evidence_id=credential-qwen-01', 'release_id=2026-08-19.1', 'target=qwen-api-key',
      'secret_backend=sops', 'old_credential_id=qwen-key-v1', 'new_credential_id=qwen-key-v2',
      'affected_services=intelligence-service', 'rollout_mode=blue-green', 'revocation_method=provider-revoked',
      'approval_reference=SEC-2026-0819', 'audit_reference=AUDIT-2026-0819',
      `rotation_started_at=${stamp(0)}`, `new_materialized_at=${stamp(5)}`,
      `rollout_completed_at=${stamp(10)}`, `rollback_started_at=${stamp(15)}`,
      `rollback_completed_at=${stamp(20)}`, `old_revoked_at=${stamp(25)}`,
      `verification_completed_at=${stamp(30)}`, 'materialization_status=passed',
      'rollout_status=passed', 'readiness_status=passed', 'smoke_status=passed',
      'rollback_status=passed', 'old_credential_rejection_status=passed', 'audit_status=passed',
      'old_credential_revoked=true', 'secrets_recorded=false', 'result=passed', '',
    ].join('\n'))
    const args = ['--evidence', evidence, '--release-id', '2026-08-19.1']
    expect(execFileSync(CREDENTIAL_EVIDENCE_VALIDATOR, args, { encoding: 'utf8' }))
      .toContain('valid for promotion')

    writeFileSync(evidence, readFileSync(evidence, 'utf8')
      .replace('old_credential_rejection_status=passed', 'old_credential_rejection_status=missing'))
    expect(spawnSync(CREDENTIAL_EVIDENCE_VALIDATOR, args, { encoding: 'utf8' }).status).toBe(1)
    writeFileSync(evidence, readFileSync(evidence, 'utf8')
      .replace('old_credential_rejection_status=missing', 'old_credential_rejection_status=passed')
      .replace('old_credential_id=qwen-key-v1', `old_credential_id=${'a'.repeat(64)}`))
    expect(spawnSync(CREDENTIAL_EVIDENCE_VALIDATOR, args, { encoding: 'utf8' }).status).toBe(1)

    const release = readFileSync(RELEASE_SCRIPT, 'utf8')
    expect(release).toContain('credential-rotation-promote --release-id ID')
    expect(release).toContain('validate-credential-rotation-evidence.sh')
  })

  it('creates a private fail-closed credential evidence worksheet without accepting key material as an ID', () => {
    const root = temporaryDirectory()
    const evidence = join(root, 'credential-worksheet.txt')
    const common = [
      '--output', evidence, '--release-id', '2026-08-19.1', '--target', 'qwen-api-key',
      '--backend', 'sops', '--old-credential-id', 'qwen-v1', '--new-credential-id', 'qwen-v2',
      '--services', 'intelligence-service', '--rollout-mode', 'blue-green',
      '--revocation-method', 'provider-revoked', '--approval-reference', 'SEC-2026-0819',
    ]
    expect(execFileSync(CREDENTIAL_EVIDENCE_CREATOR, common, { encoding: 'utf8' }))
      .toContain('worksheet created')
    expect(statSync(evidence).mode & 0o777).toBe(0o600)
    expect(readFileSync(evidence, 'utf8')).toContain('old_credential_rejection_status=pending')

    const rejectedArgs = [...common]
    rejectedArgs[1] = join(root, 'rejected.txt')
    rejectedArgs[9] = 'a'.repeat(64)
    const rejected = spawnSync(CREDENTIAL_EVIDENCE_CREATOR, rejectedArgs, { encoding: 'utf8' })
    expect(rejected.status).toBe(1)
  })

  it('materializes SOPS dotenv atomically only after the production contract passes', () => {
    const root = temporaryDirectory()
    const secretDirectory = join(root, 'secrets')
    mkdirSync(secretDirectory, { mode: 0o700 })
    const encrypted = join(root, 'production.env.enc')
    const fakeSops = join(root, 'sops')
    const output = join(secretDirectory, 'production.env')
    writeFileSync(encrypted, 'encrypted fixture')
    writeFileSync(fakeSops, '#!/usr/bin/env bash\nprintf "MATERIALIZED_FROM_SOPS=true\\n"\n', { mode: 0o700 })
    const args = ['--input', encrypted, '--output', output]
    const env = productionSecretEnvironment({ SOPS_BIN: fakeSops })

    expect(execFileSync(SECRET_MATERIALIZER, args, { env, encoding: 'utf8' }))
      .toContain('dry-run left')
    expect(() => statSync(output)).toThrow()
    expect(execFileSync(SECRET_MATERIALIZER, [...args, '--execute'], { env, encoding: 'utf8' }))
      .toContain('atomically materialized')
    expect(statSync(output).mode & 0o777).toBe(0o600)
    expect(readFileSync(output, 'utf8')).toBe('MATERIALIZED_FROM_SOPS=true\n')

    const sameFile = spawnSync(SECRET_MATERIALIZER, [
      '--input', output, '--output', output, '--execute', '--replace',
    ], { env, encoding: 'utf8' })
    expect(sameFile.status).toBe(1)
    expect(sameFile.stderr).toContain('must differ')

    writeFileSync(fakeSops, '#!/usr/bin/env bash\nexit 7\n', { mode: 0o700 })
    const failed = spawnSync(SECRET_MATERIALIZER, [...args, '--execute', '--replace'], { env, encoding: 'utf8' })
    expect(failed.status).toBe(1)
    expect(readFileSync(output, 'utf8')).toBe('MATERIALIZED_FROM_SOPS=true\n')
  })

  it('rejects production env files exposed to group or other users', () => {
    const envFile = join(temporaryDirectory(), 'production.env')
    writeFileSync(envFile, 'SESSION_SECRET=not-printed\n', { mode: 0o644 })
    const result = spawnSync(SECRET_VALIDATOR, ['--env-file', envFile], { encoding: 'utf8' })
    expect(result.status).toBe(1)
    expect(result.stderr).toContain('must not be group/world accessible')
    expect(result.stderr).not.toContain('not-printed')
  })

  it('fails closed when real provider, archive, public, or Finance policy inputs are absent', () => {
    const result = spawnSync(VIDEO_DRILL_INPUTS_SCRIPT, [], {
      encoding: 'utf8', env: { PATH: process.env.PATH, VIDEO_GENERATION_MODE: 'sandbox' },
    })
    expect(result.status).toBe(1)
    expect(result.stderr).toContain('real video drill inputs incomplete')
    expect(result.stdout).toContain('VIDEO_GENERATION_WEBHOOK_SECRET')
    expect(result.stdout).not.toContain('sk-')
  })

  it('loads dotenv values without shell evaluation and rejects command substitution', () => {
    const root = temporaryDirectory()
    const valid = join(root, 'valid.env')
    writeFileSync(valid, [
      'USER_AGENT=Mozilla/5.0 (Macintosh; Intel Mac OS X)',
      'export QUOTED="hello world (safe)"',
      '',
    ].join('\n'))
    const load = execFileSync('bash', ['-c', [
      'source "$1"',
      'load_dotenv "$2"',
      '[[ "$USER_AGENT" == "Mozilla/5.0 (Macintosh; Intel Mac OS X)" ]]',
      '[[ "$QUOTED" == "hello world (safe)" ]]',
    ].join('; '), 'bash', DOTENV_LOADER, valid], { encoding: 'utf8' })
    expect(load).toBe('')

    const marker = join(root, 'must-not-exist')
    const malicious = join(root, 'malicious.env')
    writeFileSync(malicious, `VALUE=$(touch ${marker})\n`)
    const rejected = spawnSync('bash', ['-c', [
      'source "$1"',
      'load_dotenv "$2"',
    ].join('; '), 'bash', DOTENV_LOADER, malicious], { encoding: 'utf8' })
    expect(rejected.status).toBe(1)
    expect(rejected.stderr).toContain('command substitution is forbidden')
    expect(() => readFileSync(marker)).toThrow()
  })

  it('validates a versioned rational Finance credits-to-cents policy and rejects ambiguous policy', () => {
    const validEnv = join(temporaryDirectory(), 'policy.env')
    writeFileSync(validEnv, [
      'FINANCE_CREDITS_CENTS_POLICY_VERSION=credits-cents-v1',
      'FINANCE_CREDITS_CENTS_POLICY_APPROVED_BY=finance-ticket-123',
      'FINANCE_CREDITS_CENTS_POLICY_EFFECTIVE_AT=2026-08-13T00:00:00Z',
      'FINANCE_CREDITS_CENTS_POLICY_ROUNDING=HALF_UP',
      'FINANCE_CREDITS_CENTS_POLICY_CENTS_NUMERATOR=100',
      'FINANCE_CREDITS_CENTS_POLICY_CREDITS_DENOMINATOR=1',
      'FINANCE_CREDITS_CENTS_POLICY_MAX_CENTS_PER_OPERATION=100000',
      '',
    ].join('\n'))
    expect(execFileSync(FINANCE_POLICY_SCRIPT, ['--env-file', validEnv], { encoding: 'utf8' }))
      .toContain('policy contract is valid')

    const invalidEnv = join(temporaryDirectory(), 'invalid-policy.env')
    writeFileSync(invalidEnv, readFileSync(validEnv, 'utf8')
      .replace('FINANCE_CREDITS_CENTS_POLICY_ROUNDING=HALF_UP', 'FINANCE_CREDITS_CENTS_POLICY_ROUNDING=bankers'))
    expect(spawnSync(FINANCE_POLICY_SCRIPT, ['--env-file', invalidEnv])).toMatchObject({ status: 1 })
  })
})
