import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')

function readRepositoryFile(path: string): string {
  return readFileSync(resolve(REPOSITORY_ROOT, path), 'utf8')
}

/**
 * 任务书 #101 C101-01：路由／默认值／配置透传契约（TC101-002/004 的部署层）。
 *
 * 覆盖：Edge 方法级路由注册（新 studio/微信前缀 + 旧图卡 operations GET）、两份 env
 * 模板与两份 compose 的开关透传、intelligence 服务开关映射，以及 Temporal task queue
 * 登记。默认值全部为 false（fail-closed），不含任何凭据。
 */
describe('任务书 #101 创作工作台部署契约', () => {
  const edgeYml = readRepositoryFile('platform-java/services/edge-bff/src/main/resources/application.yml')
  const intelligenceYml = readRepositoryFile(
    'platform-java/services/intelligence-service/src/main/resources/application.yml')
  const envExample = readRepositoryFile('.env.example')
  const envDockerExample = readRepositoryFile('.env.docker.example')
  const compose = readRepositoryFile('docker-compose.yml')
  const composeProduction = readRepositoryFile('docker-compose.production.yml')

  it('Edge 注册 creation-studio GET/POST/PATCH 方法级路由，默认关', () => {
    for (const method of ['GET', 'POST', 'PATCH']) {
      const block = `- method: ${method}\n      path: /api/creation-studio`
      expect(edgeYml).toContain(block)
    }
    const studioMatches = edgeYml.match(/EDGE_ROUTE_CREATION_STUDIO_INTELLIGENCE:(\w+)/g) ?? []
    expect(studioMatches).toHaveLength(3)
    expect(studioMatches.every((entry) => entry.endsWith(':false'))).toBe(true)
  })

  it('Edge 注册 creation-channels/wechat GET/POST 前缀路由，默认关', () => {
    expect(edgeYml).toContain('- method: GET\n      path: /api/creation-channels/wechat')
    expect(edgeYml).toContain('- method: POST\n      path: /api/creation-channels/wechat')
    const wechatMatches = edgeYml.match(/EDGE_ROUTE_CREATION_WECHAT_INTELLIGENCE:(\w+)/g) ?? []
    expect(wechatMatches).toHaveLength(2)
    expect(wechatMatches.every((entry) => entry.endsWith(':false'))).toBe(true)
  })

  it('Edge 补旧图卡 operations GET 前缀，沿用图卡开关（默认开）', () => {
    const block = `- method: GET\n      path: /api/card-series/operations\n      upstream: intelligence\n      enabled: \${EDGE_ROUTE_CARD_SERIES_INTELLIGENCE:true}`
    expect(edgeYml).toContain(block)
  })

  it('intelligence application.yml 登记 creation.studio/wechat 服务端开关与两个 Temporal queue', () => {
    expect(intelligenceYml).toContain('writes-enabled: ${CREATION_STUDIO_WRITES_ENABLED:false}')
    expect(intelligenceYml).toContain('visual-worker-enabled: ${CREATION_VISUAL_WORKER_ENABLED:false}')
    expect(intelligenceYml).toContain('writes-enabled: ${CREATION_WECHAT_WRITES_ENABLED:false}')
    expect(intelligenceYml).toContain('worker-enabled: ${CREATION_WECHAT_WORKER_ENABLED:false}')
    expect(intelligenceYml).toContain('task-queue: intelligence-creation-visual')
    expect(intelligenceYml).toContain('task-queue: intelligence-wechat-draft')
  })

  it('两份 env 模板声明六个开关且默认 false，不含凭据', () => {
    const flags = [
      'EDGE_ROUTE_CREATION_STUDIO_INTELLIGENCE=false',
      'EDGE_ROUTE_CREATION_WECHAT_INTELLIGENCE=false',
      'CREATION_STUDIO_WRITES_ENABLED=false',
      'CREATION_VISUAL_WORKER_ENABLED=false',
      'CREATION_WECHAT_WRITES_ENABLED=false',
      'CREATION_WECHAT_WORKER_ENABLED=false',
    ]
    for (const template of [envExample, envDockerExample]) {
      for (const flag of flags) {
        expect(template).toContain(flag)
      }
    }
    // 模板不得借机引入任何真实密钥形态
    expect(envExample).not.toMatch(/APP_?SECRET=.+\S/)
    expect(envDockerExample).not.toMatch(/APP_?SECRET=.+\S/)
  })

  it('两份 compose 透传六个开关（生产默认不启用业务）', () => {
    for (const composeFile of [compose, composeProduction]) {
      expect(composeFile).toContain('EDGE_ROUTE_CREATION_STUDIO_INTELLIGENCE:-false}')
      expect(composeFile).toContain('EDGE_ROUTE_CREATION_WECHAT_INTELLIGENCE:-false}')
      expect(composeFile).toContain('CREATION_STUDIO_WRITES_ENABLED:-false}')
      expect(composeFile).toContain('CREATION_VISUAL_WORKER_ENABLED:-false}')
      expect(composeFile).toContain('CREATION_WECHAT_WRITES_ENABLED:-false}')
      expect(composeFile).toContain('CREATION_WECHAT_WORKER_ENABLED:-false}')
    }
  })

  it('契约文件存在且模板目录无 prompt/密钥字段', () => {
    const recipesPath = resolve(REPOSITORY_ROOT, 'contracts/creation-recipes.v1.json')
    const presetsPath = resolve(REPOSITORY_ROOT, 'contracts/creation-visual-presets.v1.json')
    expect(existsSync(recipesPath)).toBe(true)
    expect(existsSync(presetsPath)).toBe(true)
    expect(readFileSync(recipesPath, 'utf8')).not.toMatch(/"prompt"|"secret"|"apiKey"/i)
  })

  // ---- 任务书 #101 C101-13：M1 验证资产契约（两入口、旧深链、feature 关闭） ----

  it('e2e spec 与隔离 fixture 存在，M1 grep 标记齐备', () => {
    const specPath = resolve(REPOSITORY_ROOT, 'tests/e2e/creation-studio.spec.ts')
    const fixturePath = resolve(REPOSITORY_ROOT, 'tests/e2e/fixtures/creation-studio.ts')
    expect(existsSync(specPath)).toBe(true)
    expect(existsSync(fixturePath)).toBe(true)
    const spec = readFileSync(specPath, 'utf8')
    expect(spec).toContain("test.describe('M1 图卡完整流程'")
    // 真实模型门槛不冒充：外部门槛显式 V-LIVE-IMAGE / NOT_RUN 语义
    expect(spec).toContain('V-LIVE-IMAGE')
    // fixture 只允许测试标识域名，不出现真实密钥形态
    const fixture = readFileSync(fixturePath, 'utf8')
    expect(fixture).toContain('@test.invalid')
    expect(fixture).not.toMatch(/sk-[A-Za-z0-9]{16,}/)
  })

  it('验收脚本三阶段齐备：m3 默认隔离模拟，真实渠道须显式授权', async () => {
    const scriptPath = resolve(REPOSITORY_ROOT, 'scripts/acceptance/verify-creation-studio.mjs')
    expect(existsSync(scriptPath)).toBe(true)
    const script = readFileSync(scriptPath, 'utf8')
    expect(script).toContain('--phase m1|m2|m3')
    // C101-22 落地：m3 已实现——默认隔离模拟，真实模式必须显式传入获授权连接 ID
    expect(script).toContain('--live-account')
    expect(script).toContain('V-LIVE-WECHAT')
    expect(script).not.toContain('NOT_IMPLEMENTED')
    // 真实模型/渠道门槛不冒充：脚本不得自动发现或使用真实密钥
    expect(script).not.toMatch(/apiKey\s*[:=]\s*['"][^'"]+/)
  })

  it('两入口共享 /article 路由且旧深链 ?draft= 保留', () => {
    // 旧深链恢复：工作区装配层读取 route.query.draft（用户端与 AI 端同一视图/composable）
    const workspace = readRepositoryFile('src/views/article/composables/useArticleWorkspace.ts')
    expect(workspace).toContain('route.query.draft')
    const aiRouter = readRepositoryFile('src/ai/router.ts')
    expect(aiRouter).toContain('article')
    const userRouter = readRepositoryFile('src/router/index.ts')
    expect(userRouter).toContain('article')
  })

  it('feature 关闭：studio 写开关默认 false 且 VisualJob/Adoption 服务端 fail-closed', () => {
    const jobService = readRepositoryFile(
      'platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/creationstudio/visual/VisualJobService.java')
    const adoptionService = readRepositoryFile(
      'platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/creationstudio/visual/VisualAdoptionService.java')
    for (const source of [jobService, adoptionService]) {
      expect(source).toContain('isWritesEnabled()')
      expect(source).toContain('STUDIO_DISABLED')
    }
  })
})
