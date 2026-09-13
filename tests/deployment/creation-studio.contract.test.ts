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
})
