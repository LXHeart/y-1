# 脚本说明

默认在仓库根目录执行脚本。稳定的 CI、发布和校验入口保留原路径，手工验收和本机临时脚本分别归类。

| 路径或命名 | 用途 |
|---|---|
| `ci-e2e.sh`、`ci-image-security.sh` | CI 浏览器集成测试和镜像检查 |
| `e2e-auth-seed.ts`、`e2e-seed.ts` | E2E 账号与业务夹具，优先使用 `npm run e2e:seed:auth`、`npm run e2e:seed` |
| `production-*.sh`、`backup-restore-drill.sh` | 发布、冒烟、灰度和灾备演练 |
| `validate-*.sh` | 对应部署、凭据、镜像、可观测性及视频演练的校验 |
| `create-credential-rotation-evidence.sh`、`materialize-production-secrets.sh`、`rotate-identity-keys.sh` | 凭据管理与轮换支持 |
| `local-observability-smoke.sh`、`local-otel-trace-smoke.sh` | 本地可观测性验证 |
| `video-*-drill.sh` | 视频回调和归档对账演练 |
| `lib/` | 数据库、环境变量和 Java 运行时共享工具 |
| `quality/`、`check-file-size.mjs` | 文档状态、链接与索引、变更覆盖率和视图体积门禁 |
| `security/` | 已跟踪文件的密钥扫描 |
| `acceptance/` | 需保存的手工浏览器验收、截图与配套造数脚本 |
| `local/` | 原根目录 `smoke-*`、`snap-*`、`verify-*` 等本机脚本；Git 忽略 |

## 文档检查

```bash
npm run docs:status
npm run docs:links
```

`docs:links` 检查 Git 跟踪及未被忽略的 Markdown（排除 `.claude/`、`.agents/`、`.codex/` 工具目录），从 `docs/README.md` 沿实际 Markdown 链接递归检查索引可达性。缺失目标、绝对本机链接、旧 `file:123` 行号链接、被忽略的普通目标或未索引文档会返回非零退出码。源码行号请写成相对路径加 `#L123`。

结果和文档清单写入 `test-artifacts/docs-links/`。两条法律运行时路由及 `test-artifacts/`、`scripts/local/` 下的本地证据只作提示，不要求新克隆具有这些产物。此命令不访问网络、不启动业务服务，也不校验页内锚点或历史代码行号。

## 手工验收入口

| 脚本（位于 `acceptance/`） | 用途与输出 |
|---|---|
| `qa-ops-layout.mjs` | 治理台合成数据布局检查 → `test-artifacts/ops-layout/` |
| `verify-task78.mjs` | 任务 #78 本地 API 验收与夹具 → `test-artifacts/task78/` |
| `shot-92-c*.mjs` | 任务 #92 分卡截图 → `docs/任务书/evidence/92/` |
| `seed-94-95.sh`、`shot-94-95.mjs` | 任务 #94/#95 本地造数及截图 → `test-artifacts/task-94/`、`task-95/` |
| `shot-review-batch1.mjs`、`reshoot-consumer-currency.mjs` | 审查批次验收和补拍 → `test-artifacts/review-batch1/` |
| `repro-accept.mjs`（本地文件） | 原根目录未跟踪的接受任务问题复现脚本，保留未跟踪状态 |

例如从仓库根执行：

```bash
node scripts/acceptance/qa-ops-layout.mjs
node scripts/acceptance/shot-92-c01.mjs
```

各脚本的服务地址、账号夹具和启动前置条件见文件开头及对应任务书。验收与造数脚本可能调用本地服务或写测试数据，不由 `npm test` 自动执行。

原本写到根目录的本机脚本已改为输出到 `test-artifacts/history/<原截图目录>/`。新脚本的产物使用 `test-artifacts/<任务>/`；原脚本的固定证据目录继续保留。
