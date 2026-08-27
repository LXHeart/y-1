-- 门店软删（2026-08-27 需求：门店只能新增、无法删除或停用）。
-- 删除=守卫通过后置 deleted_at（不物理删行——task/媒体/KYB 资料的挂接事实留在库里，
-- 与 app_users.deleted_at 同哲学）；查询侧统一过滤 deleted_at IS NULL。
-- 停用是另一个动作（status active↔suspended，可逆），不动本列。
ALTER TABLE store ADD COLUMN IF NOT EXISTS deleted_at timestamptz;
