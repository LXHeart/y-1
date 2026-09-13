import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, test } from 'vitest'

const root = resolve(import.meta.dirname, '../..')
function config(fixture: boolean) {
  const args = ['compose', '--env-file', '.env.docker.example', '-f', 'docker-compose.yml']
  if (fixture) args.push('-f', 'tests/e2e/fixtures/canvas-model.compose.yml')
  args.push('config', '--format', 'json')
  return JSON.parse(execFileSync('docker', args, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env,
    MINIO_ROOT_USER: 'fixture-root', MINIO_ROOT_PASSWORD: 'fixture-root-secret', MINIO_ACCESS_KEY: 'fixture-app', MINIO_SECRET_KEY: 'fixture-app-secret', CANVAS_E2E_PROVIDER_TOKEN: 'contract-test-token' } }))
}
describe('Canvas model fixture stays outside production', () => {
  test('default Compose has no fixture or loopback override; the test sidecar exposes no host port', () => {
    const baseline = config(false)
    expect(baseline.services['canvas-text-provider']).toBeUndefined()
    expect(baseline.services['intelligence-service'].environment.AI_PLATFORM_MODEL_ALLOW_INSECURE_LOOPBACK).toBeUndefined()
    const enabled = config(true); const service = enabled.services['canvas-text-provider']
    expect(service.image).toBe('node:20-bookworm'); expect(service.read_only).toBe(true)
    expect(service.network_mode).toBe('service:intelligence-service')
    expect(service.ports).toBeUndefined(); expect(service.volumes[0].read_only).toBe(true)
    expect(enabled.services['intelligence-service'].environment.AI_PLATFORM_MODEL_ALLOW_INSECURE_LOOPBACK).toBe('true')
  })
  test('CI opt-in uses an isolated project and existing control-plane configuration; public routing remains unchanged', () => {
    const script = readFileSync(resolve(root, 'scripts/ci-e2e.sh'), 'utf8')
    expect(script).toContain('CANVAS_E2E_TEXT_FIXTURE="${CANVAS_E2E_TEXT_FIXTURE:-0}"')
    expect(script).toContain('"$PROJECT_NAME" != "y1-e2e-task102"')
    expect(script).toContain('if [[ "$CANVAS_E2E_TEXT_FIXTURE" == "1" ]]')
    expect(script).toContain('/api/admin/ai/trusted-origins'); expect(script).toContain('/api/admin/ai/credentials')
    expect(script).toContain('/api/admin/ai/models')
    for (const file of ['nginx.conf', 'docker-compose.production.yml']) {
      const source = readFileSync(resolve(root, file), 'utf8')
      expect(source).not.toContain('canvas-text-provider'); expect(source).not.toContain('18999'); expect(source).not.toContain('/__calls')
    }
  })
})
