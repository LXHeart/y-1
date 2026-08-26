-- 任务书 #47 S4（D16）：组织 BYOK 回退策略默认翻为「允许」。
-- 独立 Flyway 历史：intelligence_flyway_schema（now at v48）。
--
-- 背景：V41（ADR-D17）把默认设为 false，语义是「组织选了 BYOK 就不准静默回退平台」。
-- 但 D15 定了商家侧「组织配了该 capability 的 key 就用、没配就走平台」，两者在
-- 「组织配了 text key、没配 image key」时直接冲突：原默认会让 org admin 配完 text 之后，
-- 图片能力对全组织成员突然 DENIED，而他完全不会预期到这件事。
--
-- 只改列默认值，**不 UPDATE 任何存量行**：已显式设过 false 的组织保持严格模式——那是他们的
-- 明示选择，不该被一次默认值翻转覆盖。无行的组织由代码侧 defaultIfEmpty(true) 承接
-- （ByokRoutingService.resolveOrgTier），两者必须同批上线，否则语义不一致。
--
-- ADR-D17 第 3 条需追加修订记录说明本次翻转（S4 文档回写项）。

ALTER TABLE ai_org_byok_policy
    ALTER COLUMN allow_platform_fallback SET DEFAULT true;

COMMENT ON COLUMN ai_org_byok_policy.allow_platform_fallback IS
    '组织密钥未覆盖某 capability 时是否允许回退平台模型；D16 起默认 true，显式 false 仍严格拒绝';
