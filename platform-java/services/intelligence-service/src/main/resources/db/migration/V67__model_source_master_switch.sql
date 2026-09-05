-- 任务书 #78 卡 B（D3）：个人「模型来源」统一总开关。（V67；V60–V66 已被视频管线等占用）
-- 约定：ai_provider_preference 增主行 capability='*'（照 ai_model_budget 的 '*/*' 全局行先例），
-- use_own_key=true 表示 own（自带密钥），false 表示 platform（平台统一模型，默认）。
-- 无主行 = platform（默认），因此无偏好数据的存量账号不需要物理回填。
--
-- 回填语义（保住存量 BYOK 用户零感知）：V49 时代 per-capability 无行即 on（D14）——任一能力
-- 配了 use_own_key=true 的账号，其个人密钥实际在参与路由，必须回填 own 主行，否则上线瞬间
-- 会静默切到平台模型并开始扣积分（ADR-D11：BYOK 0 cents，平台按积分计费）。其余有偏好行但
-- 全部为 false 的账号回填 platform 主行（与默认一致，落行仅为语义显式）。
--
-- per-capability 行保留不删（D12 可逆精神）：路由自本迁移起只读主行，旧行成为历史数据。

-- 任一能力为 true 的账号 → own 主行
INSERT INTO ai_provider_preference (account_id, capability, use_own_key, version)
SELECT DISTINCT account_id, '*', true, 1
FROM ai_provider_preference
WHERE capability <> '*' AND use_own_key = true
ON CONFLICT (account_id, capability) DO NOTHING;

-- 有偏好行但全为 false 的账号 → platform 主行（显式落行，语义与「无行默认」一致）
INSERT INTO ai_provider_preference (account_id, capability, use_own_key, version)
SELECT DISTINCT p.account_id, '*', false, 1
FROM ai_provider_preference p
WHERE p.capability <> '*'
  AND NOT EXISTS (
    SELECT 1 FROM ai_provider_preference t
    WHERE t.account_id = p.account_id AND t.use_own_key = true)
ON CONFLICT (account_id, capability) DO NOTHING;

COMMENT ON COLUMN ai_provider_preference.capability IS
    'text / image / image_generation / video_generation；''*'' = 模型来源总开关主行（任务书 #78 卡 B）';
COMMENT ON COLUMN ai_provider_preference.use_own_key IS
    '主行（capability=''*''）：true=own 自带密钥（未配密钥的能力禁用，不回退平台），false=platform 平台统一模型（默认）；能力行：false = 该能力改用平台默认模型并按积分计费（路由自 #78 起不再读能力行）';
