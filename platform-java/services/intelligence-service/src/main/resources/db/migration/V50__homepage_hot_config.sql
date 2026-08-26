-- 任务书 #47 S7b（D18①）：首页热点数据源升为平台级配置。
-- 独立 Flyway 历史：intelligence_flyway_schema（now at v50）。
--
-- 背景：数据源（60s/alapi）与 ALAPI token 此前存在 user_settings type='homepage'，每用户一份。
-- 但热点是**匿名可访问的平台数据**（/api/homepage/hot-items 未登录放行），每用户配置在缓存层面
-- 本就说不通——HomepageHotService 的 alapiCache 按 token 分键，每用户一份缓存；且与任务书 #35
-- 「同一缓存窗口标签稳定」的运营资产定位冲突。
--
-- **C 方案（保守）**：本迁移只建平台配置表，**不删任何 user_settings 行**。读取侧改为
-- 「平台配置优先，无配置则沿用原硬编码默认 60s」。存量每用户行保留但不再生效——若之后发现
-- 真有人依赖自选 alapi，数据还在，可再决定。删除是不可逆动作，与 S7 后半段清空明文同类风险
-- （用户不知情下丢配置），故不在本步做。
--
-- 单行表：用固定 id 约束「有且只有一行」，避免出现多行时读取行为不确定。

CREATE TABLE IF NOT EXISTS homepage_hot_config (
    -- 固定为 1 的单行约束：CHECK 保证不会插出第二行
    id                      int PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    provider                varchar(16) NOT NULL DEFAULT '60s'
                            CONSTRAINT chk_homepage_hot_provider CHECK (provider IN ('60s', 'alapi')),
    -- ALAPI token 走信封加密（同 BYOK / 平台凭据口径），绝不存明文；未配置为 NULL
    alapi_token_encrypted   text,
    alapi_token_key_version text,
    alapi_token_masked      text,
    version                 bigint NOT NULL DEFAULT 1,
    updated_by              text,
    updated_at              timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE homepage_hot_config IS
    '首页热点的平台级数据源配置（单行）；user_settings 的每用户配置自 S7b 起不再生效但保留';
COMMENT ON COLUMN homepage_hot_config.alapi_token_encrypted IS
    'ALAPI token 的信封加密密文；KEK 未配时 admin 写入 503，绝不退化存明文';
