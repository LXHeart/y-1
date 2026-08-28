-- 商家身份档案 org 绑定回填：「登录时先开通商家身份（不带 org）→ 工作台内建主体」的历史序列
-- 建出 organization_id=NULL 的档案，而 identity_profile 只有 create 没有 UPDATE，
-- 断言（edge 每请求查库）从此不带 org——org 级任务的鉴权与 ownerView 判定整体失效。
-- 按「一账号一主体」以 owner 关系回填；IS NULL 谓词保证幂等（迁移重放测试铁律：重放零行）。
UPDATE identity_profile ip
SET organization_id = o.id, updated_at = now()
FROM organization o
WHERE ip.account_id = o.owner_account_id
  AND ip.identity_type = 'merchant'
  AND ip.organization_id IS NULL;
