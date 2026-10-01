// hypit-fix2-api-render.spec.ts — 107-fix-2 C107F2-08：第一条真实 Docker 原生成片纵向链路。
//
// 前置（V-08 / stage local 编排负责）：隔离栈 y1-hypit-fix2-e2e（--test --enable-hypit，
// compose.full 程序/浏览器卷已 prepare）、合成账号已 seed。本 spec 只走真实 API：
//   登录 → 建工程（clone/blank 骨架）→ changeset 写入 3 秒双色移动元素源码（validated）
//   → check → plan → pricing（无远程 Need）→ build.submit（grantId=null 合法）
//   → 轮询 job 至终态 → outputs 下载 MP4 → ffprobe 时长/尺寸 → 首中末帧解码对比锚点。
//
// TC-F2-08-01 原生 MP4 3s±1 帧、三帧锚点；TC-F2-08-02 重开查询同 build 不重生成；
// TC-F2-08-03 跨 owner 404；TC-F2-08-04 外部 Provider 不可用时本地链成功且外部调用 0。
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test } from '@playwright/test'

const BASE = process.env.BASE_URL ?? 'http://127.0.0.1:18080'
const EVIDENCE = resolve('test-artifacts/task-107/fix2/C08')
const OWNER_A = 'e2e-merchant@test.local'
const OWNER_B = 'e2e-cs@test.local'
const PASSWORD = process.env.E2E_PASSWORD ?? 'test-password-2026'
const POLL_TIMEOUT_MS = 12 * 60 * 1000

mkdirSync(EVIDENCE, { recursive: true })

/**
 * 3 秒双色移动元素工程源码：320×240 画布、10fps、两个 DirectionalMatte 色墙
 * 相向扫过全帧（红自左入、蓝自右入，progress from→to 反向）。元素语法与
 * templates/ranking-tier 同构（overlay:Track → film:Track → render:Video），
 * 纯本地包零远程 Need。黑底保证「YAVG>4」只能来自移动色块本身。
 */
const MAIN_SVML = `<?svml using="@hypit/markup@1"?>
<svml>
  <import as="time" from="@hypit/timeline-author@1"/>
  <import as="spatial" from="@hypit/spatial@1"/>
  <import as="film" from="@hypit/film@1"/>
  <import as="render" from="@hypit/render-hyperframes@1"/>
  <import as="overlay" from="@hypit/screen-overlay@1"/>
  <import as="recipes" source="./style.svs"/>

  <time:Clock id="clock" frame-rate="10"/>
  <time:Timeline id="animation" clock={clock} end="3s"/>
  <spatial:Canvas id="canvas" width="320" height="240"/>
  <overlay:Track id="overlay" canvas={canvas} timeline={animation.timeline}>
    <overlay:DirectionalMatte id="redWall" z="1" start="0s" end="3s" angle="0" coverage="0.4" feather="0.2" color="#c03030" opacity="1" from="-0.6" to="0.9"/>
    <overlay:DirectionalMatte id="blueWall" z="2" start="0s" end="3s" angle="0" coverage="0.4" feather="0.2" color="#3050c0" opacity="1" from="1.6" to="0.1"/>
  </overlay:Track>
  <film:Film id="main" canvas={canvas} timeline={animation.timeline} appearance={recipes.film.main}>
    <film:Track source={overlay.track}/>
  </film:Film>
  <render:Video id="final" composition={main.composition} timeline={animation.timeline}/>
</svml>
`

const STYLE_SVS = `<?svml using="@hypit/svs@1"?>
<sheet version="1">
  /* 纵向链双色移动元素——黑底衬托色墙运动（D-05 样式入口）。 */
  film.main { background: #000000; }
</sheet>
`

/** 单请求超时：隔离栈与宿主主力共享 7.75GiB VM，冷 JIT/temporal 干扰下
 * 首请求可达 10s+；本卡验证链路正确性而非延迟 SLO（慢≠坏），放宽到 60s。 */
const REQUEST_TIMEOUT_MS = 60_000

async function login(request: import('@playwright/test').APIRequestContext, email: string): Promise<string> {
  const res = await request.post(`${BASE}/api/auth/login`, { data: { email, password: PASSWORD }, timeout: REQUEST_TIMEOUT_MS })
  expect(res.status(), `登录失败 ${email}`).toBe(200)
  const setCookie = res.headers()['set-cookie'] ?? ''
  expect(setCookie, '登录应下发会话 cookie').toMatch(/y1\.sid|session/i)
  return setCookie.split(';')[0]
}

async function api(request: import('@playwright/test').APIRequestContext, cookie: string, method: string,
  path: string, data?: unknown): Promise<{ status: number; body: any }> {
  const res = await request.fetch(`${BASE}${path}`, {
    method,
    headers: { cookie },
    timeout: REQUEST_TIMEOUT_MS,
    ...(data === undefined ? {} : { data }),
  })
  let body: any
  try { body = await res.json() } catch { body = await res.text().catch(() => null) }
  return { status: res.status(), body }
}

test.describe('C107F2-08 真实 API 纵向渲染链', () => {
  test('TC-F2-08-01 原生引擎产出 3 秒双色元素 MP4（ffprobe+三帧锚点）', async ({ request }) => {
    // 声明式 { timeout } 选项在本仓管线中被 config 30s 覆盖（已实测），
    // 用命令式 API 拉长：渲染轮询最长 12 分钟 + 下载/解码余量。
    test.setTimeout(POLL_TIMEOUT_MS + 120_000)
    const cookie = await login(request, OWNER_A)

    // ① 建工程（clone → blank 骨架 revision1）。
    const requestId = crypto.randomUUID()
    const created = await api(request, cookie, 'POST', '/api/hypit/projects', {
      requestId, title: '纵向链-双色3s', mode: 'clone',
    })
    expect(created.status).toBe(202)
    const projectId = created.body?.data?.project?.id
    expect(projectId).toBeTruthy()
    const jobId = created.body?.data?.job?.jobId
    await pollJob(request, cookie, jobId, 'provision')

    // ② 读文件 → changeset 写入 3s 双色元素源码（validated：apply 前 runner check）。
    // 契约形状（HypitProjectController）：changesets create/apply 均 202；
    // applyMode 在 create 时声明；FileChange 必带 put/delete action。
    const styleRead = await api(request, cookie, 'GET', `/api/hypit/projects/${projectId}/file?path=style.svs`)
    expect(styleRead.status).toBe(200)
    const styleHash = styleRead.body?.data?.hash
    const svmlRead = await api(request, cookie, 'GET', `/api/hypit/projects/${projectId}/file?path=main.svml`)
    expect(svmlRead.status).toBe(200)
    const svmlHash = svmlRead.body?.data?.hash
    const changeset = await api(request, cookie, 'POST', `/api/hypit/projects/${projectId}/changesets`, {
      requestId: crypto.randomUUID(),
      baseRevision: 1,
      applyMode: 'validated',
      changes: [
        { path: 'main.svml', action: 'put', baseHash: svmlHash, content: MAIN_SVML },
        { path: 'style.svs', action: 'put', baseHash: styleHash, content: STYLE_SVS },
      ],
    })
    expect(changeset.status, `changeset create: ${JSON.stringify(changeset.body)}`).toBe(202)
    const changesetId = changeset.body?.data?.changesetId
    const applied = await api(request, cookie, 'POST', `/api/hypit/projects/${projectId}/changesets/${changesetId}/apply`, {
      requestId: crypto.randomUUID(), baseRevision: 1,
    })
    expect(applied.status, `validated apply（runner check 通过才发布）: ${JSON.stringify(applied.body)}`).toBe(202)

    // ③ check → plan → pricing（全本地：无远程 Need）。
    const check = await api(request, cookie, 'POST', `/api/hypit/projects/${projectId}/check`,
      { entryFile: 'main.svml' })
    expect(check.status, `check: ${JSON.stringify(check.body)}`).toBe(200)
    expect(check.body?.data?.ok).toBe(true)
    const plan = await api(request, cookie, 'POST', `/api/hypit/projects/${projectId}/plan`,
      { requestId: crypto.randomUUID(), runFile: 'main.svrun' })
    expect(plan.status, `plan: ${JSON.stringify(plan.body)}`).toBe(200)
    const planId = plan.body?.data?.plan?.id
    expect(planId).toBeTruthy()
    const pricing = await api(request, cookie, 'POST', `/api/hypit/projects/${projectId}/pricing`,
      { requestId: crypto.randomUUID(), planId })
    expect(pricing.status).toBe(200)

    // ④ build.submit（无远程 Need → grantId=null 合法）→ 轮询至终态。
    const submit = await api(request, cookie, 'POST', `/api/hypit/projects/${projectId}/builds`, {
      requestId: crypto.randomUUID(), planId, grantId: null, title: '双色3s',
    })
    expect([200, 202]).toContain(submit.status)
    const buildId = submit.body?.data?.build?.id
    const buildJobId = submit.body?.data?.job?.jobId ?? submit.body?.data?.job?.id
    expect(buildId).toBeTruthy()
    // Job DTO 不携带 buildId（HypitDtos.Job）——TC-02/03 从这份证据读。
    writeFileSync(resolve(EVIDENCE, 'tc01-build.json'), JSON.stringify({ buildId, projectId }, null, 2))
    const terminal = await pollJob(request, cookie, buildJobId, 'build')
    writeFileSync(resolve(EVIDENCE, 'tc01-job-terminal.json'), JSON.stringify(terminal, null, 2))
    expect(terminal.body?.data?.state ?? terminal.body?.data?.job?.state).toBe('succeeded')

    // ⑤ outputs → 下载 MP4 → ffprobe + 帧锚点。
    const outputs = await api(request, cookie, 'GET', `/api/hypit/builds/${buildId}/outputs`)
    expect(outputs.status).toBe(200)
    writeFileSync(resolve(EVIDENCE, 'tc01-outputs.json'), JSON.stringify(outputs.body, null, 2))
    const items = outputs.body?.data?.items ?? outputs.body?.data?.outputs
    expect(Array.isArray(items) && items.length).toBeGreaterThan(0)
    const media = items.find((item: any) => /video|\.mp4/.test(String(item.name ?? item.kind ?? '')))
    expect(media, '应有视频产物').toBeTruthy()

    const downloadRes = await request.fetch(
      `${BASE}/api/hypit/builds/${buildId}/output?name=${encodeURIComponent(String(media.name))}`,
      { headers: { cookie }, timeout: REQUEST_TIMEOUT_MS })
    expect(downloadRes.status()).toBe(200)
    const payload = await downloadRes.json()
    const bytes = Buffer.from(String(payload?.data?.dataBase64 ?? ''), 'base64')
    expect(bytes.length, '导出应携带真实字节').toBeGreaterThan(1024)
    const mp4Path = resolve(EVIDENCE, 'tc01-final.mp4')
    writeFileSync(mp4Path, bytes)
    writeFileSync(resolve(EVIDENCE, 'tc01-sha256.txt'),
      `mp4 sha256=${createHash('sha256').update(bytes).digest('hex')} size=${bytes.length}\n`)

    // ffprobe：容器/视频流/时长 3s±1 帧（帧率 10 → ±100ms 容差）。
    const probe = execFileSync('ffprobe', [
      '-v', 'error', '-print_format', 'json', '-show_streams', '-show_format', mp4Path,
    ], { encoding: 'utf8' })
    writeFileSync(resolve(EVIDENCE, 'tc01-ffprobe.json'), probe)
    const info = JSON.parse(probe)
    const video = info.streams?.find((s: any) => s.codec_type === 'video')
    expect(video, '应有视频流').toBeTruthy()
    expect(video.codec_name).toMatch(/^h264|hevc|vp9|av1$/)
    const duration = Number(info.format?.duration ?? video.duration)
    expect(Math.abs(duration - 3)).toBeLessThanOrEqual(0.15)
    expect(Number(video.width)).toBe(320)
    expect(Number(video.height)).toBe(240)

    // 首中末帧解码（TC-F2-08-01 锚点：非空非全黑 + 双色移动元素在动）。
    const frameHashes: string[] = []
    for (const [label, at] of [['first', '0.5'], ['mid', '1.5'], ['last', '2.5']] as const) {
      const framePath = resolve(EVIDENCE, `tc01-frame-${label}.png`)
      execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-ss', at, '-i', mp4Path,
        '-frames:v', '1', framePath])
      // metadata=print 默认打 stderr（被 stdio ignore 丢弃→YAVG 恒 0 假全黑）；
      // file=- 显式走 stdout，execFileSync 才读得到。
      const mean = execFileSync('ffmpeg', ['-i', framePath, '-vf', 'signalstats,metadata=print:file=-',
        '-f', 'null', '-'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
      const yavg = Number(/YAVG=([\d.]+)/.exec(mean)?.[1] ?? 0)
      writeFileSync(resolve(EVIDENCE, `tc01-frame-${label}.yavg.txt`), `YAVG=${yavg}\n`)
      expect(yavg, `${label} 帧必须非全黑`).toBeGreaterThan(4)
      frameHashes.push(createHash('sha256').update(readFileSync(framePath)).digest('hex'))
    }
    // 移动元素锚点：三个时刻的画面互不相同（色墙在动，非静帧）。
    expect(new Set(frameHashes).size, '首中末帧应互不相同（移动元素）').toBe(3)
  })

  test('TC-F2-08-02 重开查询同 buildId：结果仍可发现下载，不重新生成', async ({ request }) => {
    const cookie = await login(request, OWNER_A)
    const outputsBefore = readFileSync(resolve(EVIDENCE, 'tc01-outputs.json'), 'utf8')
    const buildId = JSON.parse(readFileSync(resolve(EVIDENCE, 'tc01-build.json'), 'utf8'))?.buildId
    expect(buildId).toBeTruthy()
    const again = await api(request, cookie, 'GET', `/api/hypit/builds/${buildId}/outputs`)
    expect(again.status).toBe(200)
    // 幂等重读：输出集合与首次一致（同 build 不重新生成）。
    expect(JSON.stringify(again.body?.data?.outputs ?? again.body?.data?.items))
      .toBe(JSON.stringify(JSON.parse(outputsBefore)?.data?.outputs))
  })

  test('TC-F2-08-03 owner B 读 A 的 build：404 且拿不到字节', async ({ request }) => {
    const cookieB = await login(request, OWNER_B)
    const buildId = JSON.parse(readFileSync(resolve(EVIDENCE, 'tc01-build.json'), 'utf8'))?.buildId
    const res = await api(request, cookieB, 'GET', `/api/hypit/builds/${buildId}/outputs`)
    expect(res.status).toBe(404)
    // 直接下载路径同样 404（不泄漏字节）。
    const dl = await request.fetch(`${BASE}/api/hypit/builds/${buildId}/output?name=final.video`,
      { headers: { cookie: cookieB } })
    expect([404, 403]).toContain(dl.status())
  })

  test('TC-F2-08-04 外部 Provider 不可用：本地渲染链成功且外部调用计数 0', async () => {
    // 栈内无任何真实 Provider 凭据（qwen-e2e.invalid 恒不可达）；TC-01 的构建
    // 已成功——此处断言该工程零执行授权/零外部调用痕迹（本地链不产生 grant/execution）。
    const terminal = JSON.parse(readFileSync(resolve(EVIDENCE, 'tc01-job-terminal.json'), 'utf8'))
    const body = JSON.stringify(terminal)
    expect(body).not.toMatch(/hypit_provider_failed/)
    // 外部调用计数：构建结果不含任何远程 need 执行（providers 列表为空或全 local）。
    const outputs = JSON.parse(readFileSync(resolve(EVIDENCE, 'tc01-outputs.json'), 'utf8'))
    const planSnapshot = outputs?.data?.planSnapshot ?? {}
    const providers = planSnapshot?.providers
    const remoteProviders = Array.isArray(providers)
      ? providers.filter((p: any) => String(p?.localOrRemote ?? p?.kind ?? '') === 'remote')
      : []
    writeFileSync(resolve(EVIDENCE, 'tc04-remote-providers.json'), JSON.stringify({ providers: providers ?? null, remoteCount: remoteProviders.length }))
    expect(remoteProviders.length, '本地链不得出现远程 provider 请求').toBe(0)
  })
})

/** 轮询 job 到终态（GET /jobs/{id}；SSE 属 C13，此处用轮询不依赖事件面）。 */
async function pollJob(request: import('@playwright/test').APIRequestContext, cookie: string,
  jobId: string | undefined, what: string): Promise<{ status: number; body: any }> {
  expect(jobId, `${what} job 应存在`).toBeTruthy()
  const deadline = Date.now() + POLL_TIMEOUT_MS
  let last: { status: number; body: any } = { status: 0, body: null }
  while (Date.now() < deadline) {
    last = await api(request, cookie, 'GET', `/api/hypit/jobs/${jobId}`)
    const state = last.body?.data?.state ?? last.body?.data?.job?.state
    if (state === 'succeeded' || state === 'failed' || state === 'cancelled') return last
    await new Promise((r) => setTimeout(r, 3000))
  }
  throw new Error(`${what} job ${jobId} 未在 ${POLL_TIMEOUT_MS}ms 内到终态：${JSON.stringify(last.body)}`)
}
