import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')

function readRepositoryFile(path: string): string {
  return readFileSync(resolve(REPOSITORY_ROOT, path), 'utf8')
}

interface EdgeRoute {
  method: string
  path: string
  upstream: string
  exact: boolean
  enabled: string
}

/** 从 edge application.yml 文本解析 route 块（method 可缺省；exact 可缺省=false 语义）。 */
function parseRoutes(edgeYml: string): EdgeRoute[] {
  const pattern =
    /^ {4}(?:- method: (\w+)\n)? {6}path: (\S+)\n {6}upstream: (\S+)\n(?: {6}exact: (\w+)\n)? {6}enabled: (\S+)$/gm
  const routes: EdgeRoute[] = []
  for (const match of edgeYml.matchAll(pattern)) {
    routes.push({
      method: match[1] ?? '(any)',
      path: match[2],
      upstream: match[3],
      exact: match[4] === 'true',
      enabled: match[5],
    })
  }
  return routes
}

/**
 * 任务书 #108 C-02（W18）：私有文风档案 Edge 路由正反契约。
 *
 * 正向：GET/PUT `/api/creation-voice/{role}` 以 method+path 模板精确登记到 intelligence，
 * 沿用 image-analysis 风格数据族的启用条件（EDGE_ROUTE_IMAGE_ANALYSIS_STYLE_INTELLIGENCE，默认 true）。
 * 反向：API-003 preview（POST）由 C-06 完成前不得登记（fail-closed 404）；不得放开
 * `/api/creation-voice` 前缀路由（任意前缀透传）；未登记方法不因前缀命中。
 * 本测试不接真实 Docker，只校验路由登记静态契约。
 */
describe('任务书 #108 C-02 私有文风档案 Edge 路由契约', () => {
  const edgeYml = readRepositoryFile('platform-java/services/edge-bff/src/main/resources/application.yml')
  const routes = parseRoutes(edgeYml)
  const voiceRoutes = routes.filter((route) => route.path.startsWith('/api/creation-voice'))

  it('GET/PUT /api/creation-voice/{role} 精确模板登记到 intelligence，沿用 image-analysis 启用条件', () => {
    expect(voiceRoutes).toHaveLength(3)
    for (const method of ['GET', 'PUT']) {
      const route = voiceRoutes.find((entry) => entry.method === method)
      expect(route, `${method} 路由应存在`).toBeTruthy()
      expect(route!.upstream).toBe('intelligence')
      expect(route!.exact).toBe(true)
      expect(route!.path).toBe('/api/creation-voice/{role}')
      expect(route!.enabled).toBe('${EDGE_ROUTE_IMAGE_ANALYSIS_STYLE_INTELLIGENCE:true}')
    }
  })

  it('不放开 /api/creation-voice 前缀透传：全部路由 exact 且只允许 {role} 单段模板', () => {
    expect(voiceRoutes.length).toBeGreaterThanOrEqual(2)
    for (const route of voiceRoutes) {
      expect(route.exact, `${route.method} ${route.path} 必须 exact`).toBe(true)
      expect(route!.path).toMatch(/^\/api\/creation-voice\/\{role\}(\/preview)?$/)
    }
    // 不存在非 exact 的前缀路由（`path: /api/creation-voice` 裸前缀 + upstream 直连）。
    expect(voiceRoutes.some((route) => route.path === '/api/creation-voice')).toBe(false)
  })

  it('preview 仅通过精确 POST 路由开放', () => {
    const preview = voiceRoutes.find(route => route.path.endsWith('/preview'))
    expect(preview).toMatchObject({ method: 'POST', exact: true, upstream: 'intelligence', enabled: '${EDGE_ROUTE_IMAGE_ANALYSIS_STYLE_INTELLIGENCE:true}' })
    const controller = readRepositoryFile('platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/creationvoice/CreationVoiceController.java')
    expect(controller).toContain('@PostMapping(value = "/{role}/preview"')
  })

  it('解析器 fixture 合理性：yml 全量路由解析数量 > 100（防止正则失配导致假绿）', () => {
    expect(routes.length).toBeGreaterThan(100)
    expect(parseRoutes(edgeYml).some((route) => route.path === '/api/image-analysis/style-preferences')).toBe(true)
  })
})
