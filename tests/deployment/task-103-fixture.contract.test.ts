import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { fixtureAccounts, newRunId, scopedEmail, serializeManifest, emptyManifest } from '../../tests/e2e/fixtures/task-103'

/**
 * 任务书 #103 C103-24/25（TC103-24-06 / TC103-25 各卡验证命令）：
 *
 * 1) 本书真实栈验收件在位：三个专用 e2e spec、ci-e2e-103-only.sh（默认三引擎 + PARTIAL 语义）、
 *    只读 api-check 工具（SELECT-only、不打印令牌）；
 * 2) fixture 构建 runId 作用域：合成域邮箱、无全库 truncate、manifest 可序列化；
 * 3) §1.3 全模块回归地图：每个模块至少映射一条既有自动化测试文件（存在性即映射，
 *    运行证据归 V15/V18，本测试只锁映射不虚报执行）。
 */

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')

function readRepositoryFile(path: string): string {
  return readFileSync(resolve(REPOSITORY_ROOT, path), 'utf8')
}

describe('真实栈验收件在位（C103-24/25）', () => {
  it('三个专用 e2e spec 存在且覆盖六组跨域流程与浏览器验收', () => {
    const consistency = readRepositoryFile('tests/e2e/task103-consistency.spec.ts')
    expect(consistency).toContain('TC103-24-01')
    expect(consistency).toContain('TC103-24-02')
    expect(consistency).toContain('TC103-24-03')
    expect(consistency).toContain('TC103-24-04')

    const ui = readRepositoryFile('tests/e2e/task103-ui.spec.ts')
    expect(ui).toContain('TC103-25-01')
    expect(ui).toContain('TC103-25-05')

    const dispute = readRepositoryFile('tests/e2e/task103-dispute-lifecycle.spec.ts')
    expect(dispute).toContain('TC103-25-02')
    expect(dispute).toContain('TC103-25-06')
  })

  it('ci-e2e-103-only.sh：三 spec 收敛、默认三引擎、单引擎标 PARTIAL、复用 ci-e2e.sh', () => {
    const script = readRepositoryFile('scripts/acceptance/ci-e2e-103-only.sh')
    expect(script).toContain('tests/e2e/task103-consistency.spec.ts')
    expect(script).toContain('tests/e2e/task103-ui.spec.ts')
    expect(script).toContain('tests/e2e/task103-dispute-lifecycle.spec.ts')
    expect(script).toContain('chromium firefox webkit')
    expect(script).toContain('PARTIAL')
    expect(script).toContain('../ci-e2e.sh')
    // 冷静期压缩（结算后拒退 409 验证的时间窗）与 98-only 同款
    expect(script).toContain('MARKETPLACE_COMMERCE_SPLIT_COOLDOWN_SECONDS_OVERRIDE')
  })

  it('api-check 工具：SELECT-only、双旗标接口、不打印令牌/环境值', () => {
    const tool = readRepositoryFile('scripts/acceptance/task-103-api-check.ts')
    expect(tool).toContain("'--manifest'")
    expect(tool).toContain("'--output'")
    // 唯一写副作用是报告文件；数据库访问只允许 SELECT
    const dbSection = tool.slice(tool.indexOf('checkDatabaseFacts'))
    for (const forbidden of ['INSERT INTO', 'UPDATE ', 'DELETE FROM', 'DROP ', 'CREATE ', 'ALTER ']) {
      expect(dbSection).not.toContain(forbidden)
    }
    // 不把连接串/环境值写进输出
    expect(tool).not.toContain('console.log(databaseUrl')
    expect(tool).not.toContain('console.log(process.env')
  })

  it('fixture：runId 作用域 + 合成域邮箱 + 不做全库清理', () => {
    const source = readRepositoryFile('tests/e2e/fixtures/task-103.ts')
    expect(source).not.toMatch(/TRUNCATE\s+TABLE/i)
    expect(source).not.toMatch(/DROP\s+DATABASE/i)

    const first = newRunId()
    const second = newRunId()
    expect(first).toMatch(/^t103-\d{14}-[a-z0-9]{6}$/)
    expect(first).not.toBe(second)

    expect(scopedEmail(first, 'merchant', 1)).toBe(`${first}-merchant-1@example.invalid`)
    const accounts = fixtureAccounts(first, 3)
    expect(accounts.map((account) => account.role)).toEqual(['merchant', 'recommender', 'consumer'])
    expect(accounts.every((account) => account.email.endsWith('@example.invalid'))).toBe(true)

    const manifest = emptyManifest(first)
    manifest.accounts = accounts
    const serialized = serializeManifest(manifest)
    expect(serialized).toContain(first)
    // 账号排序稳定（重放可比对）
    expect(serialized).toBe(serializeManifest({ ...manifest, accounts: [...accounts].reverse() }))
  })
})

/** §1.3 全模块 → 既有自动化映射（文件存在性锁映射；执行证据归 V15/V18）。 */
describe('§1.3 全模块回归映射（TC103-24-06）', () => {
  const moduleMap: Array<[string, string]> = [
    ['Edge BFF / 三入口', 'tests/e2e/edge-entrypoint.spec.ts'],
    ['注册登录与验证码', 'src/stores/auth.session.test.ts'],
    ['身份与跨应用免登', 'src/composables/useCrossAppToken.test.ts'],
    ['主体/品牌/账号前缀', 'src/composables/useGrasslandIdentity.brand.test.ts'],
    ['门店与成员池', 'platform-java/services/identity-service/src/test/java/com/grassland/identity/membership/MembershipControllerIT.java'],
    ['KYB / 权限审核', 'tests/e2e/kyb-admin-review.spec.ts'],
    ['推荐官资料与等级', 'tests/e2e/reputation-trust-admin.spec.ts'],
    ['通知与邮件', 'platform-java/services/identity-service/src/test/java/com/grassland/identity/notification/NotificationControllerIT.java'],
    ['数据导出与注销', 'platform-java/services/identity-service/src/test/java/com/grassland/identity/compliance/ComplianceErasureReceiptIT.java'],
    ['任务草稿与发布', 'platform-java/services/marketplace-service/src/test/java/com/grassland/marketplace/taskcatalog/EngagementActionContractTest.java'],
    ['报名撮合与邀请', 'tests/e2e/grassland-task-flow.spec.ts'],
    ['交付期限与延期', 'platform-java/services/marketplace-service/src/test/java/com/grassland/marketplace/taskcatalog/EngagementDeliveryLifecycleIT.java'],
    ['体验权益', 'platform-java/services/marketplace-service/src/test/java/com/grassland/marketplace/taskcatalog/EngagementMilestoneSettlementIT.java'],
    ['草稿审稿', 'platform-java/services/marketplace-service/src/test/java/com/grassland/marketplace/taskcatalog/EngagementVerificationRepositoryIT.java'],
    ['履约凭证与核验', 'tests/e2e/grassland-task-flow.spec.ts'],
    ['里程碑与退出', 'platform-java/services/marketplace-service/src/test/java/com/grassland/marketplace/taskcatalog/NegotiatedExitIT.java'],
    ['任务结算', 'tests/e2e/task98-full-chain.spec.ts'],
    ['声誉与商家信用', 'tests/e2e/reputation-trust-admin.spec.ts'],
    ['套餐、时段与库存', 'tests/e2e/commerce-order-flow.spec.ts'],
    ['消费者订单', 'platform-java/services/marketplace-service/src/test/java/com/grassland/marketplace/commerce/CommerceControllerIT.java'],
    ['归因与推广链接', 'tests/e2e/task98-full-chain.spec.ts'],
    ['消费分账与暂扣', 'platform-java/services/marketplace-service/src/test/java/com/grassland/marketplace/commerce/CommerceSettlementRefundGateIT.java'],
    ['钱包、托管与账单', 'platform-java/services/finance-service/src/test/java/com/grassland/finance/ledger/LedgerImmutabilityIT.java'],
    ['AI 积分、BYOK 与预算', 'tests/e2e/credits-contract.spec.ts'],
    ['争议受理与举证', 'platform-java/services/trust-service/src/test/java/com/grassland/trust/dispute/DisputeEvidenceIT.java'],
    ['审判、上诉与判例', 'platform-java/services/trust-service/src/test/java/com/grassland/trust/adjudication/HearingGateIT.java'],
    ['风控、投诉与运营处置', 'platform-java/services/identity-service/src/test/java/com/grassland/identity/outbox/OutboxAtomicityIT.java'],
    ['经营分析', 'platform-java/services/marketplace-service/src/test/java/com/grassland/marketplace/commerce/CommerceStaleSnapshotIT.java'],
    ['AI 模型控制面', 'platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/ai/controlplane/TrustedOriginCacheIT.java'],
    ['创作入口与项目', 'tests/e2e/creation-studio.spec.ts'],
    ['热点与灵感', 'src/composables/useHomepageHotItems.test.ts'],
    ['文章与平台文案', 'src/composables/useArticleCreation.checkStep.test.ts'],
    ['朋友圈与创意脚本', 'src/composables/useMomentsCreation.test.ts'],
    ['图片分析与编辑', 'src/composables/useContentSafety.test.ts'],
    ['图卡与视觉内容', 'src/composables/useCardSeries.test.ts'],
    ['新图文工作台', 'platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/creationstudio/WechatDraftSyncIT.java'],
    ['排版、导出与公众号', 'platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/creationstudio/VisualPlanIT.java'],
    ['视频解析与分析', 'src/composables/useVideoTaskSession.test.ts'],
    ['视频制作', 'tests/e2e/video-canvas.spec.ts'],
    ['分镜画布', 'tests/e2e/video-canvas-closure.spec.ts'],
    ['媒体与素材库', 'src/composables/useGrasslandGovernance.storeMedia.test.ts'],
    ['语音、嵌入与语义检索', 'platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/speech/SpeechTranscriptionRepositoryIT.java'],
    ['游客体验', 'platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/guesttrial/GuestTrialControllerIT.java'],
    ['治理台 24 页签', 'src/ops/admin/AdminView.test.ts'],
    ['迁移与八个共享库', 'platform-java/services/release-migrator/src/test/java/com/grassland/releasemigrator/ReleaseMigratorUpgradeIT.java'],
  ]

  it('每个 §1.3 模块行都映射到真实存在的自动化测试文件', () => {
    expect(moduleMap.length).toBeGreaterThanOrEqual(45)
    for (const [module, file] of moduleMap) {
      expect(existsSync(resolve(REPOSITORY_ROOT, file)), `${module} → ${file}`).toBe(true)
    }
  })

  it('治理台页签唯一清单 adminTabs.ts 覆盖 24 页签（治理台行的检查锚点）', () => {
    const tabs = readRepositoryFile('src/ops/admin/adminTabs.ts')
    const ids = tabs.match(/key:\s*'([a-z-]+)'/g) ?? []
    expect(ids.length, 'adminTabs 页签数').toBeGreaterThanOrEqual(24)
  })
})
