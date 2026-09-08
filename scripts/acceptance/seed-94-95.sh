#!/bin/zsh
# 任务书 #94/#95 截图造数：finance/merchant_reviewer 账号 + 60 条处置单 + cs_direct 争议（含证据与 merchant_rejection 处置单）
set -e
cd "$(dirname "$0")/../.."
HASH=$(node -e "console.log(require('bcryptjs').hashSync('test-password-2026',10))")
DID=$(uuidgen | tr 'A-Z' 'a-z')
docker compose exec -T postgres-local psql -U grassland -d grassland -v hash="$HASH" -v did="$DID" <<'SQL'
INSERT INTO app_users(id, email, password_hash, display_name, role, status)
VALUES (gen_random_uuid(), 'finance-shot@test.local', :'hash', '财务专员(截图)', 'user', 'active')
ON CONFLICT (email) DO UPDATE SET password_hash = EXCLUDED.password_hash;
INSERT INTO app_users(id, email, password_hash, display_name, role, status)
VALUES (gen_random_uuid(), 'reviewer-shot@test.local', :'hash', '商户审核(截图)', 'user', 'active')
ON CONFLICT (email) DO UPDATE SET password_hash = EXCLUDED.password_hash;
INSERT INTO backend_role(account_id, role)
SELECT id, 'finance' FROM app_users WHERE email = 'finance-shot@test.local'
ON CONFLICT (account_id, role) DO NOTHING;
INSERT INTO backend_role(account_id, role)
SELECT id, 'merchant_reviewer' FROM app_users WHERE email = 'reviewer-shot@test.local'
ON CONFLICT (account_id, role) DO NOTHING;

-- 60 条处置单（55 高危 blocked + 5 normal held），幂等 source_ref
INSERT INTO ops_case(id, source_kind, source_ref, reason, severity)
SELECT gen_random_uuid(), 'settlement_blocked', 'shot-blk-' || g, '截图造数：对账阻断 ' || g, 'high'
FROM generate_series(1, 55) g ON CONFLICT DO NOTHING;
INSERT INTO ops_case(id, source_kind, source_ref, reason, severity)
SELECT gen_random_uuid(), 'settlement_held', 'shot-hld-' || g, '截图造数：结算暂缓 ' || g, 'normal'
FROM generate_series(1, 5) g ON CONFLICT DO NOTHING;

-- cs_direct 争议（premium，SLA 明天到期）+ 一条文本证据（含手机号验证脱敏形）
INSERT INTO dispute_case(id, engagement_ref, organization_id, opened_by_account_id, opened_by_role,
    status, reason, kind, premium_support, support_priority, channel, cs_due_at, created_at, updated_at)
VALUES (:'did', 'shot-eng-94', gen_random_uuid(),
    (SELECT id FROM app_users WHERE email = 'e2e-merchant@test.local'), 'merchant', 'open',
    '商家拒付：交付内容与约定不符，联系 13812345678 协商', 'merchant_rejection', true, 100,
    'cs_direct', now() + interval '1 day', now(), now())
ON CONFLICT DO NOTHING;
INSERT INTO dispute_evidence(id, dispute_id, submitted_by_account_id, submitted_by_role, kind, content_ref, caption, retention_until, phase)
SELECT gen_random_uuid(), :'did',
    (SELECT id FROM app_users WHERE email = 'e2e-merchant@test.local'), 'merchant', 'text',
    '聊天记录截图说明：商家电话 13987654321 表示拒收', '补充证据', now() + interval '30 days', 'claim'
WHERE NOT EXISTS (SELECT 1 FROM dispute_evidence WHERE dispute_id = :'did');

-- merchant_rejection 处置单（sourceRef=disputeId，供「前往客服裁定」演示）
INSERT INTO ops_case(id, source_kind, source_ref, reason, severity)
SELECT gen_random_uuid(), 'merchant_rejection', :'did', 'merchant_contested_verified_work', 'high'
WHERE NOT EXISTS (SELECT 1 FROM ops_case WHERE source_ref = :'did');
SQL
echo "SEEDED dispute_id=$DID"
