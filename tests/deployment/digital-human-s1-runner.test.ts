import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 数字人 S1 runner 契约（任务书 #105E C105E-06 / V105E-06-01）。
 *
 * 只断言脚本/compose/代理的结构合同：隔离合成栈、每引擎重置、无公共 provider、默认关闭扩展点、
 * 证书短命且不入库。不是运行验收（那是 V105E-06-02 的职责）。
 */

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')

function read(path: string): string {
  return readFileSync(resolve(REPOSITORY_ROOT, path), 'utf8')
}

describe('digital-human S1 runner contract (task #105E C105E-06)', () => {
  it('runner 复用 ci-e2e 隔离生命周期：串行、三引擎默认、DH 扩展显式开启、真实 provider 强制 false', () => {
    const runner = read('scripts/acceptance/ci-e2e-105-s1.sh')

    expect(runner).toContain('E2E_WORKERS="${E2E_WORKERS:-1}"')
    expect(runner).toContain('E2E_ENGINES="${E2E_ENGINES:-chromium firefox webkit}"')
    expect(runner).toContain('export DH_E2E=1')
    expect(runner).toContain('export DH_REAL_PROVIDERS_ENABLED=false')
    // 先证书后栈（compose secrets 依赖证书就位）。
    const certCall = runner.indexOf('task-105-test-certificates.sh')
    const execCall = runner.indexOf('exec bash scripts/ci-e2e.sh')
    expect(certCall).toBeGreaterThan(-1)
    expect(execCall).toBeGreaterThan(certCall)
  })

  it('ci-e2e.sh 的 DH 扩展点默认关闭：旧入口（canvas/缺省）不加载 DH compose', () => {
    const ci = read('scripts/ci-e2e.sh')
    expect(ci).toContain('${DH_E2E:-0}" == "1"')
    // 缺省 compose 文件列表不含 DH；仅 DH_E2E 分支追加。
    const dcBody = ci.slice(ci.indexOf('dc() {'), ci.indexOf('capture_failure_logs'))
    expect(dcBody).toContain('deploy/digital-human/compose.test.yml')
    // canvas 变体与 DH 变体互不干扰（各自独立 if）。
    expect(dcBody).toContain('CANVAS_E2E_TEXT_FIXTURE')
  })

  it('DH compose：只合成栈——无持久化内容 Redis、Fake runtime 无真实 provider、密钥只走 secrets', () => {
    const compose = read('deploy/digital-human/compose.test.yml')

    // 内容/资格 Redis 无持久化（K05：禁 AOF/RDB/备份）。
    expect(compose).toContain('--save')
    expect(compose).toContain('""')
    expect(compose).toContain('--appendonly no')
    expect(compose).not.toMatch(/redis.*(volume|volumes)/)

    // Fake runtime：真实 provider 关闭；不出现任何 provider key/URL/模型名 env。
    expect(compose).toContain('DH_REAL_PROVIDERS_ENABLED: "false"')
    expect(compose).not.toMatch(/API_KEY|api_key|sk-[A-Za-z0-9]{8}|provider_url|PROVIDER_URL/)

    // mTLS 材料经 secrets（文件在 test-artifacts 本地目录，不入库）。
    expect(compose).toContain('secrets:')
    expect(compose).toContain('dh-client-key')
    const secretFiles = [...compose.matchAll(/file: \.\/test-artifacts\/[^\s]+$/gm)]
    expect(secretFiles.length).toBeGreaterThanOrEqual(3)
  })

  it('测试证书脚本：短命、只写 test-artifacts 本地目录、私钥 600 且不入库', () => {
    const script = read('scripts/acceptance/task-105-test-certificates.sh')

    expect(script).toContain('test-artifacts/task-105/E/dh-test-certs')
    expect(script).toContain('VALIDITY_DAYS=2')
    expect(script).toContain('chmod 600')
    // SAN 与 K13.2 对齐（双向身份）。
    expect(script).toContain('digital-human-runtime')
    expect(script).toContain('intelligence-service')
    // 幂等：已有有效证书不重造。
    expect(script).toContain('checkend')

    // 私钥不入库：目标目录被 Git 忽略（本地生成是设计行为，不入库才是断言对象）。
    expect(read('.gitignore')).toContain('test-artifacts/')
  })

  it('本地测试代理：麦克风 self 与生产 AI 入口一致；SSE 不聚合；音频 WS 精确形态到 runtime 9080', () => {
    const conf = read('deploy/digital-human/nginx.test.conf')

    expect(conf).toContain('microphone=(self)')
    expect(conf).toContain('camera=()')
    // SSE：缓冲关闭 + 长读超时（K06 注释心跳不能被代理聚合）。
    expect(conf).toContain('proxy_buffering off')
    // 音频 WS：精确路径升级到 wrapper 9080；其余 /api/ 走 edge。
    expect(conf).toMatch(/location ~ \^\/api\/digital-human\/sessions\/\[\^\/\]\+\/audio\$/)
    expect(conf).toContain('proxy_pass http://dh-runtime:9080')
    expect(conf).toContain('proxy_pass http://edge-bff')
    // 不冒充生产配置（H 验证生产模板）。
    expect(conf).toContain('H 阶段验证生产模板')
  })

  it('e2e spec 不 mock 后端集成：无 route.fulfill；mic 路径显式 PARTIAL 标注', () => {
    const spec = read('tests/e2e/digital-human-workbench.spec.ts')
    expect(spec).not.toMatch(/page\.route\(|route\.fulfill\(/)
    expect(spec).toContain('PARTIAL')
    // 完整流用例对媒体桥依赖做运行期探测并给出精确跳过原因（不静默 skip）。
    expect(spec).toContain('test.skip(')
    expect(spec).toContain('dh_runtime_unavailable')
    const fixture = read('tests/e2e/fixtures/digital-human.ts')
    expect(fixture).not.toMatch(/page\.route\(|route\.fulfill\(/)
    expect(fixture).toContain('synthetic')
  })
})
