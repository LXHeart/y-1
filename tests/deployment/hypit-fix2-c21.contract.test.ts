import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * C107F2-21（107-fix-2 / F17）iframe 安全策略契约（W156）。
 *
 * 守护 nginx/片段/组件三方不变量：
 *  - Studio 会话页（auth_request 鉴权后）允许同源嵌入：XFO SAMEORIGIN + CSP
 *    frame-ancestors 'self' 仅按 /studio/<id>/ URI 切换；其余路径（用户端/AI/治理）
 *    保持 DENY / frame-ancestors 'none' 不回退；
 *  - auth_request 子请求走固定 Edge access 路由、透传 Cookie（站内身份）、不透出
 *    内部 token；出站断言头由 proxy_set_header 无条件覆盖（客户端伪造被丢弃）；
 *  - ticket 一次性查询串不进访问日志（专用 $uri 格式）；
 *  - StudioPanel iframe sandbox 仅脚本+同源，ticketUrl 过期不进 iframe。
 */

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')

function read(path: string): string {
  return readFileSync(resolve(REPOSITORY_ROOT, path), 'utf8')
}

describe('C107F2-21 iframe 安全策略（TC-F2-21 契约面）', () => {
  it('TC-F2-21-01 Studio 局部放宽：URI map 切 SAMEORIGIN + frame-ancestors self；默认仍 DENY/none', () => {
    const nginx = read('nginx.conf')
    // URI 切换的 XFO：default DENY，studio 路径 SAMEORIGIN。
    expect(nginx).toContain('map $uri $hypit_frame_options')
    expect(nginx).toMatch(/default\s+"DENY"/)
    expect(nginx).toContain('"SAMEORIGIN"')
    expect(nginx).toMatch(/{[^{}]*~\^\/studio\/[^{}]*\}/s)
    // studio CSP 变体：frame-ancestors 'self'；主 CSP 变体保持 'none'。
    expect(nginx).toContain('$csp_policy_enforced_studio')
    expect(nginx).toMatch(/frame-ancestors 'self'; report-uri \/csp-report/)
    expect(nginx).toMatch(/frame-ancestors 'none'; report-uri \/csp-report/)
    // 只有 82 server 引用切换变量；80/81 维持字面量 DENY。
    const server80 = nginx.slice(nginx.indexOf('listen 80'), nginx.indexOf('listen 81'))
    const server81 = nginx.slice(nginx.indexOf('listen 81'), nginx.indexOf('listen 82'))
    const server82 = nginx.slice(nginx.indexOf('listen 82'))
    expect(server80).toContain('add_header X-Frame-Options "DENY" always')
    expect(server81).toContain('add_header X-Frame-Options "DENY" always')
    expect(server80).not.toContain('$hypit_frame_options')
    expect(server81).not.toContain('$hypit_frame_options')
    expect(server82).toContain('add_header X-Frame-Options $hypit_frame_options always')
    expect(server82).toContain('add_header Content-Security-Policy $csp_policy_enforced_by_uri always')
  })

  it('TC-F2-21-02 auth_request 面固定 Edge access 路由；断言头覆盖伪造；内部 token 不透出', () => {
    const fragment = read('deploy/hypit/nginx.locations.conf')
    // 固定内部子请求 + internal（外部不可直打）。
    expect(fragment).toContain('location = /_hypit_session_access')
    expect(fragment).toMatch(/internal;/)
    // C107F2-37 修正：路由契约是路径参 /api/hypit/sessions/{sid}/access——旧扁平别名
    // ?sid= 被 Edge exact 路由表 404，auth_request 把 404 升级成 500。
    expect(fragment).toContain('proxy_pass http://$dh_edge_upstream/api/hypit/sessions/$studio_session/access?$args;')
    expect(fragment).not.toContain('?sid=$studio_session')
    expect(fragment).toContain('proxy_pass_request_body off')
    // 出站断言头来自 auth_request_set 变量（覆盖客户端伪造同名头）。
    expect(fragment).toContain('auth_request /_hypit_session_access;')
    expect(fragment).toContain('auth_request_set $hypit_session_assertion $upstream_http_x_hypit_session_assertion;')
    expect(fragment).toContain('proxy_set_header X-Hypit-Session-Assertion $hypit_session_assertion;')
    // 片段不出现内部 token（它只走 Java internal 面，与浏览器入口无关）。
    expect(fragment).not.toMatch(/HYPIT_INTERNAL_TOKEN|internal-token/i)
  })

  it('TC-F2-21-03 ticket 查询串禁入访问日志（专用 $uri 格式）', () => {
    const nginx = read('nginx.conf')
    const fragment = read('deploy/hypit/nginx.locations.conf')
    // log_format 用 $uri（无 query）——ticket 不落日志。
    expect(nginx).toContain("log_format hypit_studio")
    expect(nginx).toMatch(/hypit_studio[^;]*"\$request_method \$uri \$server_protocol"/)
    expect(fragment).toContain('access_log /var/log/nginx/access.log hypit_studio;')
  })

  it('TC-F2-21-04 StudioPanel：sandbox 仅脚本+同源，过期 ticketUrl 不进 iframe，主页面策略不回退', () => {
    const panel = read('src/views/video-clone/components/StudioPanel.vue')
    expect(panel).toContain('sandbox="allow-scripts allow-same-origin"')
    // 过期会话不渲染旧 ticketUrl（§6.9：失效可重开，不带入失效 URL）。
    expect(panel).toMatch(/Date\.parse\(session\.expiresAt\) <= Date\.now\(\)/)
    // 三入口 CSP 框架守护仍在（不因 studio 放宽全局关闭）。
    const nginx = read('nginx.conf')
    expect((nginx.match(/add_header Content-Security-Policy-Report-Only/g) ?? []).length).toBe(3)
    expect((nginx.match(/X-Frame-Options/g) ?? []).length).toBeGreaterThanOrEqual(3)
  })
})
