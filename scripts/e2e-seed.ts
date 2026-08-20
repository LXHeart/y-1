/**
 * 草场 e2e 种子数据——一键建齐跑通端到端所需的账号/组织/身份/审判官/资金账户。
 *
 * 背景：e2e 改用本地 postgres 后每次都是全新库，此前靠手工 SQL 逐个建，
 * 踩过「先跑 legacy 迁移」「商家档案已存在无法改绑 org」等坑。本脚本把这套固化下来。
 *
 * 用法：
 *   npm run e2e:seed              # 幂等，可重复执行
 *   npm run e2e:seed -- --reset   # 清理脚本声誉样本和登录态，再恢复可审计的测试状态
 *
 * 全部写入幂等（ON CONFLICT），可安全重跑。
 * 前置：须先运行 Java `database-bootstrap`（Compose E2E 已自动执行）。
 */
import bcrypt from 'bcryptjs'
import { closeDbPool, queryDb } from './lib/db'

/** 统一测试口令——e2e 脚本与手工验证共用，可通过 E2E_PASSWORD 覆盖。 */
const PASSWORD = process.env.E2E_PASSWORD || 'test-password-2026'
const MERCHANT_EMAIL = 'e2e-merchant@test.local'
const CS_EMAIL = 'e2e-cs@test.local'
/** 平台管理员：D-05 权限审核队列的门禁是 `app_users.role == 'admin'`。 */
const ADMIN_EMAIL = 'e2e-admin@test.local'
const JUDGE_COUNT = 7
const ORG_NAME = 'E2E 测试商家'
const REPUTATION_SEED_TITLE_PREFIX = 'E2E reputation seed:'
const MAX_REPUTATION_TOP_UP = 5_000

interface SeedResult {
  merchantId: string
  csId: string
  adminId: string
  orgId: string
  judgeIds: string[]
}

async function upsertUser(email: string, role: string, passwordHash: string): Promise<string> {
  const existing = await queryDb<{ id: string }>('SELECT id FROM app_users WHERE email = $1', [email])
  if (existing.rows.length > 0) {
    // 已存在则只校正 role/status（口令不覆盖，避免踩掉手工改过的值）
    await queryDb("UPDATE app_users SET role = $1, status = 'active' WHERE email = $2", [role, email])
    return existing.rows[0].id
  }
  const created = await queryDb<{ id: string }>(
    `INSERT INTO app_users(id, email, password_hash, status, role)
     VALUES (gen_random_uuid(), $1, $2, 'active', $3) RETURNING id`,
    [email, passwordHash, role])
  return created.rows[0].id
}

/** 组织：一个 owner 一个测试组织；tier 直接给 finance_transaction 以便验证资金型任务。 */
async function upsertOrganization(ownerAccountId: string): Promise<string> {
  const existing = await queryDb<{ id: string }>(
    'SELECT id FROM organization WHERE name = $1 AND owner_account_id = $2', [ORG_NAME, ownerAccountId])
  if (existing.rows.length > 0) {
    await queryDb("UPDATE organization SET permission_tier = 'finance_transaction' WHERE id = $1",
      [existing.rows[0].id])
    return existing.rows[0].id
  }
  const created = await queryDb<{ id: string }>(
    `INSERT INTO organization(id, owner_account_id, name, status, permission_tier, industry)
     VALUES (gen_random_uuid(), $1, $2, 'active', 'finance_transaction', 'other') RETURNING id`,
    [ownerAccountId, ORG_NAME])
  return created.rows[0].id
}

/**
 * 组织成员：给 owner 补一行 OWNER 记录。
 *
 * 真实创建路径（`POST /api/organizations`）会顺带种这行，而种子此前直接 INSERT organization、
 * 跳过了它 —— 鉴权靠 `owner_account_id` 兜底所以一直没暴露，但**成员列表是空的**，
 * 与真实创建出来的组织行为不一致（浏览器实测「成员与门店」卡片时发现）。
 */
async function upsertOwnerMembership(organizationId: string, accountId: string): Promise<void> {
  await queryDb(
    `INSERT INTO organization_membership(id, organization_id, account_id, role)
     VALUES (gen_random_uuid(), $1, $2, 'owner')
     ON CONFLICT (organization_id, account_id) DO UPDATE SET role = 'owner'`,
    [organizationId, accountId])
}

/**
 * 身份档案。UNIQUE(account_id, identity_type) 冲突时**更新 organization_id**——
 * 这正是手工建数据时踩过的坑：商家档案先以无 org 建出，之后无法经 API 改绑（409）。
 */
async function upsertIdentityProfile(
  accountId: string, identityType: string, organizationId: string | null): Promise<void> {
  await queryDb(
    `INSERT INTO identity_profile(id, account_id, identity_type, organization_id, status)
     VALUES (gen_random_uuid(), $1, $2, $3, 'active')
     ON CONFLICT (account_id, identity_type)
     DO UPDATE SET organization_id = EXCLUDED.organization_id, status = 'active'`,
    [accountId, identityType, organizationId])
}

/**
 * 为审判 E2E 补足真实 Lv5 必要事实。按完成数、完成率和评分的实际缺口补 5 星样本，
 * 已满足门槛时重复执行不会继续膨胀。
 * 这些行只服务本地测试；生产等级仍完全从真实履约事实派生。
 */
async function ensureLv5Reputation(
  accountId: string, merchantId: string, organizationId: string, adminId: string): Promise<void> {
  const current = await queryDb<{
    completed_count: number
    terminal_count: number
    rating_count: number
    rating_sum: number
    min_completed: number
    min_completion_rate: number
    min_average_score: number | null
  }>(
    `SELECT
       (SELECT COUNT(*) FROM task_application
         WHERE recommender_account_id = $1 AND confirmed_at IS NOT NULL)::int AS completed_count,
       (SELECT COUNT(*) FROM task_application
         WHERE recommender_account_id = $1 AND confirmed_at IS NOT NULL)::int
         + (SELECT COUNT(*) FROM task_application
             WHERE recommender_account_id = $1 AND status IN ('refunded', 'rejected', 'withdrawn'))::int
         AS terminal_count,
       (SELECT COUNT(*) FROM engagement_rating
         WHERE recommender_account_id = $1)::int AS rating_count,
       (SELECT COALESCE(SUM(score), 0) FROM engagement_rating
         WHERE recommender_account_id = $1)::int AS rating_sum,
       (SELECT min_completed FROM reputation_level_rule WHERE level_number = 5)::int AS min_completed,
       (SELECT min_completion_rate FROM reputation_level_rule WHERE level_number = 5)::float8
         AS min_completion_rate,
       (SELECT min_average_score FROM reputation_level_rule WHERE level_number = 5)::float8
         AS min_average_score`,
    [accountId])
  const stats = current.rows[0]
  const completedCount = Number(stats?.completed_count || 0)
  const terminalCount = Number(stats?.terminal_count || 0)
  const ratingCount = Number(stats?.rating_count || 0)
  const ratingSum = Number(stats?.rating_sum || 0)
  if (stats?.min_completed == null || stats.min_completion_rate == null) {
    throw new Error('Lv5 reputation policy is missing')
  }
  const minCompleted = Number(stats.min_completed)
  const minCompletionRate = Number(stats.min_completion_rate)
  const minAverageScore = stats?.min_average_score == null ? null : Number(stats.min_average_score)
  if (!Number.isFinite(minCompleted) || !Number.isFinite(minCompletionRate)) {
    throw new Error('Lv5 reputation policy is missing or invalid')
  }
  if (minCompletionRate >= 1 && completedCount < terminalCount) {
    throw new Error(`Cannot reach Lv5 completion rate 100% for account ${accountId} by adding completed samples`)
  }
  if (minAverageScore != null && minAverageScore >= 5 && ratingSum < 5 * ratingCount) {
    throw new Error(`Cannot reach Lv5 average score 5.0 for account ${accountId} by adding 5-star samples`)
  }
  const completionRateTopUp = minCompletionRate >= 1
    ? 0
    : Math.ceil(Math.max(0,
      (minCompletionRate * terminalCount - completedCount) / (1 - minCompletionRate)))
  const completionSampleTopUp = minCompletionRate > 0 && terminalCount === 0 ? 1 : 0
  const ratingTopUp = minAverageScore == null || minAverageScore >= 5
    ? 0
    : Math.ceil(Math.max(0,
      (minAverageScore * ratingCount - ratingSum) / (5 - minAverageScore)))
  const ratingSampleTopUp = minAverageScore != null && ratingCount === 0 ? 1 : 0
  const missing = Math.max(
    0, minCompleted - completedCount, completionRateTopUp, completionSampleTopUp,
    ratingTopUp, ratingSampleTopUp)
  if (missing > MAX_REPUTATION_TOP_UP) {
    throw new Error(
      `Lv5 policy requires ${missing} seed samples for account ${accountId}; ` +
      `maximum is ${MAX_REPUTATION_TOP_UP}`)
  }

  if (missing > 0) {
    await queryDb(
      `WITH new_tasks AS (
         INSERT INTO task(id, owner_account_id, organization_id, title, status, published_at)
         SELECT gen_random_uuid(), $2::uuid, $3::uuid,
                $4::text || $1::text || ':' || sequence::text, 'closed',
                -- 回拨到上月末：发布配额按 published_at 的自然月计数（TaskRepository
                -- countCreatedThisMonthByOrganization），700 条 Lv5 种子任务若落在本月会
                -- 烧尽组织 500/月配额，令首个发布任务的 e2e 撞 409（2026-08-20 实测）。
                -- 声誉的 30 天活跃窗口读 application 的时间戳，不受回拨影响。
                date_trunc('month', now()) - interval '1 day'
           FROM generate_series(1, $5::int) AS sequence
         RETURNING id
       ), new_applications AS (
         INSERT INTO task_application(
           id, task_id, recommender_account_id, status, reviewed_by_account_id,
           decided_at, confirmed_at, bounty_cents, reputation_level_at_accept,
           reputation_policy_version_at_accept, settlement_delay_days_at_accept,
           commission_bonus_bps_at_accept, premium_support_at_accept)
         SELECT gen_random_uuid(), id, $1::uuid, 'accepted', $2::uuid, now(), now(), 0,
                1, 1, 2, 0, false
           FROM new_tasks
         RETURNING id, task_id
       )
       INSERT INTO engagement_rating(
         id, application_id, task_id, recommender_account_id, rated_by_account_id, score, comment)
       SELECT gen_random_uuid(), id, task_id, $1::uuid, $2::uuid, 5, 'E2E Lv5 seed'
         FROM new_applications`,
      [accountId, merchantId, organizationId, REPUTATION_SEED_TITLE_PREFIX, missing])
  }

  await ensureLv5Admission(accountId, adminId)
}

/** 恢复 seed 所需的 Lv5 当前态；从 revoked 恢复时同时追加不可变管理审计。 */
async function ensureLv5Admission(accountId: string, adminId: string): Promise<void> {
  await queryDb(
    `INSERT INTO reputation_lv5_admission(account_id, admitted, version, updated_by, note)
     VALUES ($1, true, 1, $2, 'E2E seed')
     ON CONFLICT (account_id) DO NOTHING`,
    [accountId, adminId])
  await queryDb(
    `WITH current_state AS (
           SELECT account_id, admitted, version, updated_by, note, updated_at
             FROM reputation_lv5_admission
            WHERE account_id = $1::uuid
            FOR UPDATE
         ), changed AS (
           UPDATE reputation_lv5_admission admission
              SET admitted = true,
                  version = admission.version + 1,
                  updated_by = $2::uuid,
                  note = 'E2E seed restore',
                  updated_at = now()
             FROM current_state before_state
            WHERE admission.account_id = before_state.account_id
              AND before_state.admitted = false
            RETURNING admission.account_id,
                      before_state.admitted AS before_admitted,
                      before_state.version AS before_version,
                      before_state.updated_by AS before_updated_by,
                      before_state.note AS before_note,
                      before_state.updated_at AS before_updated_at,
                      admission.admitted AS after_admitted,
                      admission.version AS after_version,
                      admission.updated_by AS after_updated_by,
                      admission.note AS after_note,
                      admission.updated_at AS after_updated_at
         )
         INSERT INTO reputation_admin_audit(
             action, target_account_id, actor_account_id, actor_role,
             policy_version, admission_version, note, before_snapshot, after_snapshot)
         SELECT 'lv5_granted', account_id, $2::uuid, 'platform_admin',
                NULL, after_version, 'E2E seed restore',
                jsonb_build_object(
                    'accountId', account_id, 'admitted', before_admitted,
                    'version', before_version, 'updatedBy', before_updated_by,
                    'note', before_note, 'updatedAt', before_updated_at),
                jsonb_build_object(
                    'accountId', account_id, 'admitted', after_admitted,
                    'version', after_version, 'updatedBy', after_updated_by,
                    'note', after_note, 'updatedAt', after_updated_at)
           FROM changed`,
    [accountId, adminId])
}

/** 审判官入池并完成运营准入：平台级，避免与争议组织同组织被冲突排除。 */
async function upsertJudge(accountId: string, adminId: string): Promise<void> {
  await queryDb(
    `INSERT INTO judge(
       id, account_id, organization_id, eligibility_tier, active,
       ops_admitted, ops_admitted_at, ops_admitted_by)
     VALUES (gen_random_uuid(), $1, NULL, 5, true, true, now(), $2)
     ON CONFLICT (account_id) DO UPDATE SET
       eligibility_tier = 5,
       active = true`,
    [accountId, adminId])
  await queryDb(
    `WITH current_state AS (
           SELECT id, account_id, version, ops_admitted, ops_admitted_at, ops_admitted_by
             FROM judge
            WHERE account_id = $1::uuid
            FOR UPDATE
         ), changed AS (
           UPDATE judge candidate
              SET ops_admitted = true,
                  version = candidate.version + 1,
                  ops_admitted_at = now(),
                  ops_admitted_by = $2::uuid
             FROM current_state before_state
            WHERE candidate.id = before_state.id
              AND before_state.ops_admitted = false
            RETURNING candidate.id, before_state.version AS before_version,
                      candidate.version AS after_version,
                      before_state.ops_admitted AS before_admitted,
                      before_state.ops_admitted_at AS before_admitted_at,
                      before_state.ops_admitted_by AS before_admitted_by,
                      candidate.ops_admitted AS after_admitted,
                      candidate.ops_admitted_at AS after_admitted_at,
                      candidate.ops_admitted_by AS after_admitted_by
         )
         INSERT INTO judge_admission_audit(
             judge_id, action, actor_account_id, reason, previous_version, new_version)
         SELECT id, 'granted', $2::uuid, 'E2E seed restore', before_version, after_version
           FROM changed`,
    [accountId, adminId])
}

/** 资金账户 + 初始余额（sandbox），供资金型任务的预留/结算链路使用。 */
async function upsertFinanceAccount(organizationId: string, balanceCents: number): Promise<void> {
  await queryDb(
    `INSERT INTO finance_account(id, organization_id, balance_cents, currency)
     VALUES (gen_random_uuid(), $1, $2, 'CNY')
     ON CONFLICT (organization_id) DO UPDATE SET balance_cents = GREATEST(finance_account.balance_cents, $2)`,
    [organizationId, balanceCents])
}

/** --reset：只清掉本脚本生成的声誉事实和登录态，保留账号 ID、审计及真实 E2E 履约数据。 */
async function reset(): Promise<void> {
  const users = await queryDb<{ id: string }>(
    "SELECT id FROM app_users WHERE email LIKE 'e2e-%@test.local'")
  const ids = users.rows.map((r) => r.id)
  if (ids.length === 0) return
  // 只删除本脚本生成的声誉事实，保留测试过程中产生的真实报名和评分。
  await queryDb(
    `DELETE FROM engagement_rating rating
      USING task_application application, task seeded_task
      WHERE rating.application_id = application.id
        AND application.task_id = seeded_task.id
        AND seeded_task.title LIKE $1`,
    [`${REPUTATION_SEED_TITLE_PREFIX}%`])
  await queryDb(
    `DELETE FROM task_application application
      USING task seeded_task
      WHERE application.task_id = seeded_task.id
        AND seeded_task.title LIKE $1`,
    [`${REPUTATION_SEED_TITLE_PREFIX}%`])
  await queryDb(
    `DELETE FROM task_version version
      USING task seeded_task
      WHERE version.task_id = seeded_task.id
        AND seeded_task.title LIKE $1`,
    [`${REPUTATION_SEED_TITLE_PREFIX}%`])
  await queryDb('DELETE FROM task WHERE title LIKE $1', [`${REPUTATION_SEED_TITLE_PREFIX}%`])

  // 审计表不可 UPDATE/DELETE；账号、judge 和当前准入均保留原 ID，seed 会恢复其测试状态并追加审计。
  await queryDb('DELETE FROM identity_session WHERE account_id = ANY($1::uuid[])', [ids])
  console.log(`[reset] 已清理 ${ids.length} 个 e2e 账号的脚本声誉事实；账号、历史审计和真实履约记录均保留`)
}

async function seed(): Promise<SeedResult> {
  const passwordHash = bcrypt.hashSync(PASSWORD, 10)

  // 商家（也是组织 owner）
  const merchantId = await upsertUser(MERCHANT_EMAIL, 'user', passwordHash)
  const orgId = await upsertOrganization(merchantId)
  await upsertOwnerMembership(orgId, merchantId)
  await upsertIdentityProfile(merchantId, 'merchant', orgId)
  await upsertFinanceAccount(orgId, 1_000_000)  // ¥10000，够跑多轮资金型任务

  // 客服（role 判定，非业务身份；终审还需 5 分钟内重认证）
  const csId = await upsertUser(CS_EMAIL, 'customer_service', passwordHash)

  // 平台管理员（D-05 权限审核队列 `/api/admin/permission-requests` 的门禁）
  const adminId = await upsertUser(ADMIN_EMAIL, 'admin', passwordHash)
  await queryDb(
    `INSERT INTO backend_role(account_id, role)
     VALUES ($1::uuid, 'platform_admin')
     ON CONFLICT (account_id, role) DO NOTHING`,
    [adminId])

  // 审判官池：平台级，且各自具备 recommender 身份（投票门禁要求「推荐官 + 已入池」）
  const judgeIds: string[] = []
  for (let i = 1; i <= JUDGE_COUNT; i += 1) {
    const id = await upsertUser(`e2e-judge${i}@test.local`, 'user', passwordHash)
    await upsertIdentityProfile(id, 'recommender', null)
    await ensureLv5Reputation(id, merchantId, orgId, adminId)
    await upsertJudge(id, adminId)
    judgeIds.push(id)
  }

  return { merchantId, csId, adminId, orgId, judgeIds }
}

async function main(): Promise<void> {
  if (process.argv.includes('--reset')) {
    await reset()
  }
  const result = await seed()

  console.log('\n=== e2e 种子数据就绪 ===')
  console.log(`口令（全部账号）: ${PASSWORD}`)
  console.log(`商家:   ${MERCHANT_EMAIL}  (${result.merchantId})`)
  console.log(`组织:   ${ORG_NAME}  (${result.orgId})  tier=finance_transaction  余额=¥10000`)
  console.log(`客服:   ${CS_EMAIL}  (${result.csId})  role=customer_service`)
  console.log(`管理员: ${ADMIN_EMAIL}  (${result.adminId})  role=admin（权限审核队列）`)
  console.log(`审判官: e2e-judge1..${JUDGE_COUNT}@test.local  (${result.judgeIds.length} 名，均为 Lv5 且已运营准入)`)
  console.log('\n提示：')
  console.log('  · 审判官投票需先激活 recommender 活动身份（POST /api/me/active-identity {"type":"recommender"}）')
  console.log('  · 客服终审需 5 分钟内重认证（POST /api/me/reauthenticate {"password":"..."}）')
  console.log('  · 商家操作需激活 merchant 活动身份')
}

main()
  .then(() => {
    process.exitCode = 0
  })
  .catch((error: unknown) => {
    console.error('[e2e-seed] 失败:', error instanceof Error ? error.message : error)
    process.exitCode = 1
  })
  .finally(closeDbPool)
