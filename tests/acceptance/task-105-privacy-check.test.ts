import { mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterAll, describe, expect, it } from 'vitest'
import { loadManifest, scanBuffer, scanTree } from '../../scripts/acceptance/task-105-privacy-check'

/**
 * 任务书 #105G C105G-05 / TC105G-05-01 Vitest 侧：隐私扫描器正文/密钥/SDP 反例。
 * 规则契约：原 mic/key/grant/SDP 永远禁止（fixture 源文件也不豁免）；prompt 正文仅
 * 合成 fixture 源文件豁免，日志永不豁免；空目录不能通过。
 */
const manifest = loadManifest(resolve('tests/fixtures/digital-human/privacy-manifest.json'))
const { prompt, mic, key, sdp } = manifest.canaries

const tmpRoot = resolve('test-artifacts/task-105/G/C105G-05/scan-tests')

afterAll(() => {
  rmSync(tmpRoot, { recursive: true, force: true })
})

describe('tc105g_05_01 · 隐私扫描器（正文/密钥/SDP 反例）', () => {
  it('正文 marker：日志文件命中即违规，fixture 源文件豁免', () => {
    const logViolations = scanBuffer('G/C105G-05/run.log', Buffer.from(`turn input: ${prompt}\n`, 'utf8'), manifest)
    expect(logViolations).toHaveLength(1)
    expect(logViolations[0]).toMatchObject({ kind: 'canary', canary: 'prompt' })

    const fixtureViolations = scanBuffer('fixtures/digital-human/canary-source.txt', Buffer.from(prompt, 'utf8'), manifest)
    expect(fixtureViolations).toHaveLength(0)
  })

  it('密钥 marker：任何文件（含 fixture 源）永远禁止', () => {
    for (const path of ['logs/ops.log', 'fixtures/keydump.txt']) {
      const violations = scanBuffer(path, Buffer.from(`credential=${key}\n`, 'utf8'), manifest)
      expect(violations).toHaveLength(1)
      expect(violations[0]).toMatchObject({ kind: 'canary', canary: 'key', file: path })
    }
  })

  it('SDP 与原始 mic marker：任何文件永远禁止', () => {
    const sdpHit = scanBuffer('trace/net.log', Buffer.from(`offer=${sdp}`, 'utf8'), manifest)
    expect(sdpHit).toHaveLength(1)
    expect(sdpHit[0]).toMatchObject({ canary: 'sdp' })

    const micHit = scanBuffer('G/C105G-05/audio.log', Buffer.from(mic, 'utf8'), manifest)
    expect(micHit).toHaveLength(1)
    expect(micHit[0]).toMatchObject({ canary: 'mic' })
  })

  it('grant 形态值（fake-grant-*）：按模式匹配判违规', () => {
    const hit = scanBuffer('logs/grant.log', Buffer.from('issued fake-grant-audio-Ab3xY9pQ2zLmN4kR', 'utf8'), manifest)
    expect(hit.some((v) => v.kind === 'grant')).toBe(true)
    const clean = scanBuffer('logs/grant.log', Buffer.from('no grants here', 'utf8'), manifest)
    expect(clean).toHaveLength(0)
  })

  it('干净文件零违规；UTF-8 中文内容不误报', () => {
    const violations = scanBuffer('G/C105G-05/report.md', Buffer.from('这是第一段确定回复。第二段说明口播结构。', 'utf8'), manifest)
    expect(violations).toHaveLength(0)
  })
})

describe('tc105g_05_01 · 目录树扫描与空目录门禁', () => {
  it('空目录不能通过（无产物可证）', () => {
    const empty = resolve(tmpRoot, 'empty-root')
    mkdirSync(empty, { recursive: true })
    const report = scanTree(empty, manifest)
    expect(report.scannedFiles).toBe(0)
    expect(report.violations).toHaveLength(1)
    expect(report.violations[0].kind).toBe('empty-root')
  })

  it('混批目录：fixture 源豁免正文、日志违规逐文件报告', () => {
    const root = resolve(tmpRoot, 'mixed')
    mkdirSync(resolve(root, 'fixtures/digital-human'), { recursive: true })
    mkdirSync(resolve(root, 'logs'), { recursive: true })
    writeFileSync(resolve(root, 'fixtures/digital-human/canary-source.txt'), prompt, 'utf8')
    writeFileSync(resolve(root, 'logs/clean.log'), 'all good\n', 'utf8')
    writeFileSync(resolve(root, 'logs/leak.log'), `input=${prompt}`, 'utf8')
    writeFileSync(resolve(root, 'logs/secret.log'), `${key}${sdp}`, 'utf8')

    const report = scanTree(root, manifest)
    expect(report.scannedFiles).toBe(4)
    expect(report.skippedFixtureFiles).toBe(1)
    const files = report.violations.map((v) => v.file).sort()
    expect(files).toEqual(['logs/leak.log', 'logs/secret.log', 'logs/secret.log'])
    const canaries = report.violations.map((v) => v.canary).sort()
    expect(canaries).toEqual(['key', 'prompt', 'sdp'])
  })
})
