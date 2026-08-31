-- 任务书 #59 后续：平台 provider 受控值集从「厂商名」改为「协议方言名」。
--
-- 旧值集 qwen | openai-compatible | sandbox 里，qwen 从来只是个标签：分方言之前
-- TextCompletionClient 没有任何 provider 分支，qwen 与 openai-compatible 走的是逐字节
-- 相同的代码路径（同一 chat/completions、同一 Bearer、同一请求体）。所以把 qwen 平移到
-- openai-completions 是**行为中性**的——运行期请求一模一样，只是名字终于说了实话。
--
-- 新值集：openai-completions | openai-responses | anthropic-messages
--         | google-generative-ai | openai-compatible | sandbox
-- 单一真相源在 PlatformProviderNames（四个控制面 DTO 的正则 + PlatformProviderPolicy 白名单
-- 都从那里取），本迁移只负责把存量行搬到新值集内。
--
-- 为什么这一步是**必须**而非可选：DTO 正则已收窄到不含 qwen。存量 qwen 行不迁，
-- 治理台一 PUT 就 400、行变成不可编辑的死行——PlatformModelConfigControllerIT:166
-- 记录过同一个坑（收窄正则却不迁数据）。
--
-- 幂等/可重放：UPDATE ... WHERE provider = 'qwen' 第二次跑匹配 0 行
-- （OutboxRepositoryIT / TaskLifecycleMigrationTest 重放红线）。

-- ① 平台凭据。唯一索引是 (provider, base_url) WHERE enabled——openai-completions 是全新名字，
--    存量行不可能已持有它，重命名不会撞索引；停用行不在部分索引内，更不受影响。
UPDATE platform_provider_credential
   SET provider = 'openai-completions',
       updated_at = now()
 WHERE provider = 'qwen';

-- ② 平台模型配置当前表。唯一索引是 (capability, model_role) WHERE enabled，不含 provider。
UPDATE platform_model_config
   SET provider = 'openai-completions'
 WHERE provider = 'qwen';

-- ③ platform_model_config_history 故意**不动**：它是按值存的不可变审计快照、无 FK、
--    也从不回读进 DTO 校验。历史行必须保留「当时写的是什么」，改写等于篡改审计。

-- 列注释落当前真相（V46/V7 里的 `-- qwen / openai-compatible / sandbox` 行内注释已随文件
-- 被 Flyway 校验和固定，不能回改；用 COMMENT ON 把新值集带到当前 schema 上）。
COMMENT ON COLUMN platform_provider_credential.provider IS
    '协议方言名：openai-completions | openai-responses | anthropic-messages | google-generative-ai | openai-compatible | sandbox。受控值集单一真相源见 PlatformProviderNames。';

COMMENT ON COLUMN platform_model_config.provider IS
    '协议方言名，取值同 platform_provider_credential.provider；决定 TextDialects 解析出的请求/响应线形状。';
