# deploy/hypit — 任务书 #107-3 C107-23 部署套件

Hypit 视频克隆引擎（broker + 隔离 runner + 完整运行时）的生产部署、备份与恢复。
规约来源：任务书 §5.2 环境参数固定表、K05/K06 数据生命周期、K10.4 runner 隔离、K13.3 入口装配。

## 拓扑

```
浏览器 ── nginx (ai 入口 82, /studio/<id>/ 票据路径) ── hypit_studio 上游（overlay 配置；空=404）
intelligence-service ── HYPIT_SIDECAR_BASE_URL(http://hypit-backend:9240, 容器内部) ── hypit-backend（可信 broker）
hypit-backend ── Unix socket 卷（唯一通道）── hypit-author-runner（无网络/只读根/非 root/全弃权）
```

- `Dockerfile.backend`：Node 24.14.1 + backend 包 + 引擎源/补丁；ffmpeg/ffprobe；G 在启动期由
  `build-107-engine.sh --no-install` 物化（或直接预构建进镜像）。大体积资产（uv 程序环境、浏览器缓存、
  模型）不进镜像层，走卷。
- `Dockerfile.runner`：author 项目代码执行槽。K10.4 硬隔离：`network_mode: none`、uid 10001、
  `read_only`、`cap_drop: ALL`、`no-new-privileges`；tmpfs scratch 有硬配额；不挂 Docker socket、
  宿主 home、数据库与 broker 凭据。
- `nginx.locations.conf`：AI server 专用 include 片段。`HYPIT_STUDIO_UPSTREAM` 为空时 `/studio/*`
  一律 404（禁用≠无法启动，nginx -t 仍过）；同源 Origin 校验、WS 升级只放行 `/__studio/ws`。

## 唯一组合与启停入口（107-fix-2 C02，替代手工 -f 拼接）

```bash
# 可审核计划（不执行任何变更）：
bash scripts/acceptance/hypit-compose.sh plan
# 显式启用（profile + Java enable + Edge 旗标 + Studio upstream + 密钥预检）：
HYPIT_INTERNAL_TOKEN=…(≥32) HYPIT_STUDIO_TICKET_SECRET=…(≥32) \
  bash scripts/acceptance/hypit-compose.sh up --enable-hypit
# 隔离测试组合（工程 y1-hypit-fix2-e2e，端口 18080/18081/18082；CI/验收用）：
bash scripts/acceptance/hypit-compose.sh --test --enable-hypit --enable-dh up
```

固定次序（D-02）：`docker-compose.yml → docker-compose.production.yml →
deploy/digital-human/compose.production.yml → deploy/hypit/compose.production.yml
→ [compose.full.yml]`。Hypit overlay 恒在组合内——共享服务（frontend/Edge/Java）
的 compose labels 因此恒含 overlay（修复 F01「两套启动组合互相覆盖」）。

- **默认 fail-closed**：`HYPIT_ENABLED=false`、全部 `EDGE_ROUTE_HYPIT_*=false`、
  `HYPIT_STUDIO_UPSTREAM=` 空（片段 404）；只经 `--enable-hypit` 显式启用。
- **密钥预检**（TC-F2-02-04）：启用时 `HYPIT_INTERNAL_TOKEN` 与
  `HYPIT_STUDIO_TICKET_SECRET` 必须 ≥32 字符；缺项/过短立即非零退出，
  不执行任何 docker 变更、不生成生产默认密钥。隔离测试组合除外（一次性值，不落仓库）。
- **防覆盖预检**（TC-F2-02-03）：`up`/`build` 前对比工程既有容器 labels 的组合；
  已启用模块被本次组合静默移除时非零拒绝；显式 `--disable-hypit`/`--disable-dh`
  才允许停用并打印受影响模块。
- **生产栈安全**：生产模式默认工程名 `y-1`；本入口对正在运行的栈只提供
  `plan`/`config` 可审核输出，实际 `up` 需运维显式执行。

旧的三层手工叠加（V12 时代）仍可用但不再推荐：

```bash
docker compose -f docker-compose.yml -f docker-compose.production.yml \
               -f deploy/hypit/compose.production.yml [-f deploy/hypit/compose.full.yml] config
```

环境模板见 [.env.example](.env.example)。`HYPIT_INTERNAL_TOKEN` 启用时必填且不进 runner；
`HYPIT_STUDIO_TICKET_SECRET` 启用时必填（≥32 字符，F04）。

## 运行时准备与程序

```bash
# 管理的 Python 程序（whisperx.local / image.opencv.local；uv --frozen，不改锁）：
deploy/hypit/prepare-programs.sh [programsRoot] [distributionRoot]
# broker 侧程序控制：
curl -X POST http://127.0.0.1:9240/internal/v1/...   # 内部 token 端点仅本地/可信网
#   POST /api/hypit/runtime/programs/up {"program":"whisperx.local"}（经 intelligence 可信面）
```

浏览器双版本从各包 manifest 读取并记入 deployment record；prepare 的下载项/日志落
`install.log`，网络不可用时 status 不得写 ready（readiness 分层：installed → configured →
prepared → healthy）。

## 运行时路径（GET /api/hypit/runtime/paths）

公网 API 只回**逻辑键**（`<dataRoot>/projects` 等七键，`hostPathsRevealed:false`）——与 107-1
§6.2「真实宿主路径只向部署者显示」一致。部署者获取真实宿主路径：登录宿主执行

```bash
docker compose -f deploy/hypit/compose.full.yml config | grep -A2 volumes   # 数据卷宿主绑定
# 或直接查 env：HYPIT_DATA_ROOT / HYPIT_STATE_ROOT（deploy/hypit/.env）
```

`captureBrowserCache` 逻辑键为 null 表示 capture 采集浏览器缓存目录未配置（D-07 键
`HYPIT_CAPTURE_BROWSER_CACHE`，见 `.env.example`）；配置后该键回配置值（仍非宿主绝对路径面）。

## 备份与恢复

```bash
deploy/hypit/backup.sh <output-dir>        # 维护租约→PG 快照→数据伞打包→manifest v2→释放租约
HYPIT_PG_DSN=… deploy/hypit/restore.sh <backup-dir> <new-data-root>        # 正式恢复（必传 DSN）
HYPIT_PG_DSN=… deploy/hypit/restore.sh <backup-dir> <new-data-root> --files-only   # 诊断 PARTIAL
```

恢复（C107F2-34，report `y1.hypit-restore-report@2`）：只落空目标卷；归档顶层先路径
预验证（穿越/绝对路径/roots 不齐拒绝）后显式映射 TARGET_ROOT（内容在目标之内，无旁边
误落目录）；正式恢复必传 PG DSN，`--files-only` 仅诊断且退出码 3（PARTIAL，不参与
full 通过）；恢复后按真实 schema（`hypit_revision.number/snapshot_handle/manifest_hash`）
逐路径逐 hash 核对，缺 snapshot/hash 不一致按类别计数且非零退出（0 READY / 2 输入 /
3 PARTIAL / 4 核验失败 / 5 PG 失败）；全程零 generation 触发。

- 备份内容（C107F2-33，manifest `y1.hypit-backup@2`）：PG（pg_dump -Fc，`pgDumpSha256`）+
  整个数据伞（HYPIT_DATA_ROOT 及其 /data 兄弟目录：projects/work/revisions、resources、
  results、profiles、programs 锁、独立 credentials——一个不漏）；临时面（runner-slots/
  runner-sockets/runner-tmp/.staging/package-transfers/cache）明确 `omitted` 不进归档。
- `files[]` 逐 sha256/sizeBytes 与归档严格一致；`roots[]` 带角色；`complete=true` 只在
  全部步骤成功后原子写入——任何一步失败非零退出且无 complete 清单。
- 维护窗经 leaseId 租约（§6.15）：`drained=false` 打印在途业务 ID 后释放自己的租约并
  非零退出；EXIT/INT/TERM 陷阱幂等释放；409=他人窗口立即失败。同一已排空窗口内完成
  PG 与文件快照（不混 revision）。
- 归档件（dump/tarball/manifest）权限 0600；凭据按既有受控加密存储原样进归档，内容与
  路径不打印；**不入仓库**（secret-scan 门禁）。
- 隔离演练 fixture：`scripts/acceptance/hypit-backup-fixture.sh <target-dir>`（受控
  pg_dump/psql 桩 + 数据伞 + 临时面 + fail-tar 注入桩；不触真实 PG/用户卷）。
- 恢复强制 sha256 校验、拒绝非空目标、产出 `restore-report.json`（revision 快照缺口、孤儿
  build 行计数）；overlay 回滚/停用时数据卷与表一律保留。

## 分层验收（107-fix-2 C39/C40，唯一验收入口）

`bash scripts/acceptance/verify-107-fix-2.sh --stage <stage>` 按层验收，各层只解析自身
实际产物（`test-artifacts/task-107/fix2/<层>/results.json`），不以日志关键词或「上次跑过」
代替判定：

| stage | 覆盖 | 必需服务 |
|---|---|---|
| `card --card C107F2-XX` | 单卡登记测试分派 + typecheck | 按卡登记（多数无需 Docker） |
| `local` | 真实 API 原生 render 纵向链（C08 四组 TC） | 隔离栈 `y1-hypit-fix2-e2e` |
| `e2e` | 三引擎浏览器链（C36/C37） | 同上，经 ci-e2e 完整隔离生命周期 |
| `recovery` | kill/重启/维护/备份→新 PG+卷恢复（C38） | 任务隔离栈，绝不触碰主栈 |
| `all` | 以上各层产物汇总 + G1–G7 Gate 映射 | 读产物 + 契约层实跑 |

判定纪律：任一层 exitCode≠0 / executed=0 / 缺必需 TC 即非零并逐项列出；LIVE（真实商业
Provider）未授权恒 NOT_RUN，本地全绿的表述上限是 **LOCAL_PASS（107-fix-2 本地交付验收）**，
不得写成全平台全量通过。镜像建议预构建后以 `HYPIT_COMPOSE_NO_BUILD=1` 复用（省 daemon 出网）。

## 合同测试

`tests/deployment/hypit-compose.contract.test.ts` 与 `hypit-entrypoint.contract.test.ts`
守护以上全部不变量（profile 门禁、隔离键、env 表默认值、nginx 空上游 404、根接线镜像层分发）。
