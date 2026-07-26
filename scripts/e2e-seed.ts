/**
 * 草场 e2e 种子数据——一键建齐跑通端到端所需的账号/组织/身份/审判官/资金账户。
 *
 * 背景：e2e 改用本地 postgres 后每次都是全新库，此前靠手工 SQL 逐个建，
 * 踩过「先跑 legacy 迁移」「商家档案已存在无法改绑 org」等坑。本脚本把这套固化下来。
 *
 * 用法：
 *   npm run e2e:seed              # 幂等，可重复执行
 *   npm run e2e:seed -- --reset   # 先清空既有 e2e 数据再建（换 org 归属等场景）
 *
 * 全部写入幂等（ON CONFLICT），可安全重跑。
 * ⚠️ 前置：须先跑 `npm run db:migrate:local`（建 legacy 的 session/app_users 等表）。
 */
import bcrypt from 'bcryptjs'
import { queryDb } from '../server/src/lib/db'

/** 统一测试口令——e2e 脚本与手工验证共用。 */
const PASSWORD = 'E2ePass!2026'
const MERCHANT_EMAIL = 'e2e-merchant@test.local'
const CS_EMAIL = 'e2e-cs@test.local'
/** 平台管理员：D-05 权限审核队列的门禁是 `app_users.role == 'admin'`。 */
const ADMIN_EMAIL = 'e2e-admin@test.local'
const JUDGE_COUNT = 7
const ORG_NAME = 'E2E 测试商家'

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

/** 审判官入池：平台级（organization_id=NULL），避免与争议组织同组织被冲突排除。 */
async function upsertJudge(accountId: string): Promise<void> {
  await queryDb(
    `INSERT INTO judge(id, account_id, organization_id, eligibility_tier, active)
     VALUES (gen_random_uuid(), $1, NULL, 1, true)
     ON CONFLICT (account_id) DO UPDATE SET active = true`,
    [accountId])
}

/** 资金账户 + 初始余额（sandbox），供资金型任务的预留/结算链路使用。 */
async function upsertFinanceAccount(organizationId: string, balanceCents: number): Promise<void> {
  await queryDb(
    `INSERT INTO finance_account(id, organization_id, balance_cents, currency)
     VALUES (gen_random_uuid(), $1, $2, 'CNY')
     ON CONFLICT (organization_id) DO UPDATE SET balance_cents = GREATEST(finance_account.balance_cents, $2)`,
    [organizationId, balanceCents])
}

/** --reset：清掉本脚本建的 e2e 数据（按 email 前缀/组织名定位，不碰其它数据）。 */
async function reset(): Promise<void> {
  const users = await queryDb<{ id: string }>(
    "SELECT id FROM app_users WHERE email LIKE 'e2e-%@test.local'")
  const ids = users.rows.map((r) => r.id)
  if (ids.length === 0) return
  // 先删引用方，再删被引用方
  await queryDb('DELETE FROM judge WHERE account_id = ANY($1::uuid[])', [ids])
  await queryDb('DELETE FROM identity_profile WHERE account_id = ANY($1::uuid[])', [ids])
  await queryDb('DELETE FROM organization_membership WHERE account_id = ANY($1::uuid[])', [ids])
  await queryDb('DELETE FROM identity_session WHERE account_id = ANY($1::uuid[])', [ids])
  const orgs = await queryDb<{ id: string }>(
    'SELECT id FROM organization WHERE owner_account_id = ANY($1::uuid[])', [ids])
  const orgIds = orgs.rows.map((r) => r.id)
  if (orgIds.length > 0) {
    await queryDb('DELETE FROM finance_account WHERE organization_id = ANY($1::uuid[])', [orgIds])
    await queryDb('DELETE FROM organization WHERE id = ANY($1::uuid[])', [orgIds])
  }
  await queryDb('DELETE FROM app_users WHERE id = ANY($1::uuid[])', [ids])
  console.log(`[reset] 已清理 ${ids.length} 个 e2e 账号及关联数据`)
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

  // 审判官池：平台级，且各自具备 recommender 身份（投票门禁要求「推荐官 + 已入池」）
  const judgeIds: string[] = []
  for (let i = 1; i <= JUDGE_COUNT; i += 1) {
    const id = await upsertUser(`e2e-judge${i}@test.local`, 'user', passwordHash)
    await upsertIdentityProfile(id, 'recommender', null)
    await upsertJudge(id)
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
  console.log(`审判官: e2e-judge1..${JUDGE_COUNT}@test.local  (${result.judgeIds.length} 名，均已入池)`)
  console.log('\n提示：')
  console.log('  · 审判官投票需先激活 recommender 活动身份（POST /api/me/active-identity {"type":"recommender"}）')
  console.log('  · 客服终审需 5 分钟内重认证（POST /api/me/reauthenticate {"password":"..."}）')
  console.log('  · 商家操作需激活 merchant 活动身份')
}

main()
  .then(() => process.exit(0))
  .catch((error: unknown) => {
    console.error('[e2e-seed] 失败:', error instanceof Error ? error.message : error)
    process.exit(1)
  })
