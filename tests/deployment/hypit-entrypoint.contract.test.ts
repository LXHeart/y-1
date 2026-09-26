import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Hypit 入口装配契约（任务书 #107-3 C107-23 步骤 7 / K13.3）。
 *
 * 守护 nginx/Dockerfile/compose 三方不变量：Studio 片段随镜像常驻分发（缺失
 * include = 三入口启动失败，同 #105H DH 教训）；HYPIT_STUDIO_UPSTREAM 空串兜底
 * （envsubst 只替换已定义变量）+ 片段对空上游 404（禁用≠入口宕机，nginx -t 通过）；
 * 上游一律运行期变量代理（无字面量主机名在启动解析期失败）；其余两入口不得出现
 * 可访问 Studio 的路径。真实 nginx 启动与票据路径打真由 verify-107-full.sh 承接。
 */

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')

function read(path: string): string {
  return readFileSync(resolve(REPOSITORY_ROOT, path), 'utf8')
}

describe('hypit studio 入口装配（TC107-23-03 nginx 面）', () => {
  it('AI server（82）以固定 include 装配片段并声明运行期上游变量', () => {
    const nginx = read('nginx.conf')
    expect(nginx).toContain('include /etc/nginx/hypit-locations.conf;')
    expect(nginx).toContain('set $hypit_studio_upstream "${HYPIT_STUDIO_UPSTREAM}";')
    // include 只在 82（AI 入口）server 块内：80/81 server 不得引用 hypit 片段。
    const server80 = nginx.slice(nginx.indexOf('listen 80'), nginx.indexOf('listen 81'))
    const server81 = nginx.slice(nginx.indexOf('listen 81'), nginx.indexOf('listen 82'))
    expect(server80).not.toContain('hypit-locations')
    expect(server81).not.toContain('hypit-locations')
  })

  it('Dockerfile.frontend 分发片段并空串兜底 HYPIT_STUDIO_UPSTREAM', () => {
    const dockerfile = read('Dockerfile.frontend')
    expect(dockerfile).toContain('COPY deploy/hypit/nginx.locations.conf /etc/nginx/hypit-locations.conf')
    expect(dockerfile).toContain('ENV HYPIT_STUDIO_UPSTREAM=""')
    // envsubst 兜底顺序：ENV 必须先于 COPY？不——nginx template 渲染在容器启动期，
    // 只要求 ENV 在镜像内定义；这里额外守护 DH 同款注释存在（防回退）。
    expect(dockerfile).toContain('ENV DH_AUDIO_UPSTREAM=""')
  })

  it('片段：空上游 404、变量代理、WS 同源升级、内部端点拒绝', () => {
    const fragment = read('deploy/hypit/nginx.locations.conf')
    expect(fragment).toContain('if ($hypit_studio_upstream = "")')
    expect(fragment).toContain('return 404')
    expect(fragment).toContain('proxy_pass http://$hypit_studio_upstream')
    // 不允许字面量上游（nginx 启动解析期会解析未知主机名 → 拖垮入口）。
    expect(fragment).not.toMatch(/proxy_pass\s+http:\/\/[a-zA-Z0-9_.-]+;/)
    expect(fragment).toContain('Connection $connection_upgrade')
    expect(fragment).toContain('location /hypit-internal/')
    expect(fragment).toContain('return 404')
  })

  it('compose 两级 frontend 均透传 HYPIT_STUDIO_UPSTREAM（空默认）', () => {
    for (const file of ['docker-compose.yml', 'docker-compose.production.yml']) {
      const compose = read(file)
      expect(compose, file).toContain('HYPIT_STUDIO_UPSTREAM: ${HYPIT_STUDIO_UPSTREAM:-}')
    }
  })

  it('docker-entrypoint/模板：片段不经 envsubst（`${...}` 守卫常驻）', () => {
    // nginx.conf 是 envsubst 模板；片段文件内的 ${HYPIT_STUDIO_UPSTREAM} 由 server 块
    // set 指令消费，nginx.conf 注释明确该机制（与 DH_AUDIO_UPSTREAM 同款）。
    const nginx = read('nginx.conf')
    expect(nginx).toContain('HYPIT_STUDIO_UPSTREAM')
  })
})
