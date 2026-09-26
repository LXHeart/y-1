# Hypit 视频克隆运行手册

> 面向维护者/部署者。规约：任务书 #107-3 §9.2「部署、环境与配置」；环境参数固定表见
> 任务书 §5.2 与 [deploy/hypit/.env.example](../../deploy/hypit/.env.example)。

## 1. 快速启动（本地隔离 e2e 栈）

```bash
# 三浏览器 + 真实 Node broker + intelligence HYPIT 面（一次性令牌脚本内生成）：
bash scripts/acceptance/ci-e2e-107.sh
# 产物：playwright-report/ 与 test-artifacts/（trap 清理本次进程/容器）
```

仅契约层（无 Docker）：

```bash
npx vitest run tests/deployment/hypit-compose.contract.test.ts \
              tests/deployment/hypit-entrypoint.contract.test.ts \
              tests/deployment/hypit-full-coverage.contract.test.ts
```

## 2. 生产部署（三层 overlay，V12）

```bash
docker compose -f docker-compose.yml -f docker-compose.production.yml config -q
HYPIT_INTERNAL_TOKEN=<≥32字节> docker compose -f docker-compose.yml -f docker-compose.production.yml \
  -f deploy/hypit/compose.production.yml config -q
# 完整变体再叠 -f deploy/hypit/compose.full.yml
```

- 默认（不带 overlay）：生产行为与 #107 之前逐字一致，无 hypit 容器。
- `--profile hypit` 才创建 `hypit-backend` / `hypit-author-runner`；缺
  `HYPIT_INTERNAL_TOKEN` 直接硬失败。
- readiness 分层：installed → configured → prepared → healthy；`GET /health` 只读，
  不触发安装/下载；网络不可用时 status 不得写 ready。

## 3. 运行时准备（大资产不进镜像层）

```bash
# 管理的 Python 程序环境（whisperx.local / image.opencv.local；uv --frozen 不改锁；
# WhisperX 显式准备模型/对齐/NLTK——inference 永不下载）：
deploy/hypit/prepare-programs.sh [programsRoot] [distributionRoot]
```

- 浏览器双版本从包 manifest 读取（capture 153.0.8010.12 / render 152.0.7928.2 Headless
  Shell），各自原生 installer/cache/probe；render 环境默认 `browserGpu=software`、
  `defaultConcurrency=1`（毕设基线，可调大）。
- 渲染 CPU 环境须实测 ffmpeg 编解码器、字体与中文 fallback。

## 4. 备份与恢复

```bash
deploy/hypit/backup.sh <output-dir>        # 维护模式→PG 快照→数据根打包→manifest→恢复副作用
deploy/hypit/restore.sh <backup-dir> <new-data-root>   # 只落空目录；sha256 校验；不发新 generation
```

- 备份内容：PG（hypit_*）+ workspace/revisions/results/profiles/程序锁/独立 credentials。
- 恢复产出 `restore-report.json`（revision 快照缺口、孤儿 build 行）；启动服务前人工核对。
- 停用 overlay / 回滚：数据卷与表一律保留。

## 5. 验证分层与 opt-in

| 层 | 入口 | 触发条件 |
|---|---|---|
| 契约 | `verify-107-upstream.sh` + 三个部署契约测试 | 常规 CI（hypit-contract job） |
| 本地 e2e | `ci-e2e-107.sh`（4 spec × 3 引擎 + 真实 broker） | workflow_dispatch 勾选 hypit-e2e |
| 完整本地 | `HYPIT_FULL_E2E=1 verify-107-full.sh`（镜像/最小渲染/恶意组件/备份恢复） | workflow_dispatch 勾选 hypit-full |
| live | `HYPIT_LIVE_ENABLED=1 + HYPIT_LIVE_BUDGET_CENTS>0 verify-107-live.sh` | workflow_dispatch + secrets |

`verify-107-full.sh` 退出语义：`0`=全阶段绿；`2`=阶段0 绿但完整阶段 NOT_RUN（逐项注明
NOT_RUN[n] 与解除条件）；`1`=真失败。`verify-107-live.sh` 未启用时 `NOT_ENABLED` + 非零退出，
真实费用未知记 null，secret 永不输出终端。

## 6. 排障

- **入口 502/404**：先查 edge 旗标（`EDGE_ROUTE_HYPIT_*` 默认全关）与
  `HYPIT_ENABLED`；两者独立，缺一即 fail-closed（capabilities 例外，探测 disabled 仍 200）。
- **`/studio/` 全 404**：frontend 的 `HYPIT_STUDIO_UPSTREAM` 为空——按设计（禁用≠宕机）；
  启用须填 broker 侧 Studio 上游地址。
- **provision 卡 provisioning_failed**：查 broker 日志的 sidecar 回执；同 requestId 重试收敛。
- **validated 保存失败**：草稿保留、head 不动；按诊断修语法后重新 apply。
- **变体局部失败**：逐项 retry（attempt 递增），批次其余项不受影响。
- **runner 逃逸嫌疑**：`B/tests/engine/runner-isolation.test.ts` 必须绿；容器四键
  （network none/read_only/cap_drop ALL/no-new-privileges）任何缺失都不得上线。

## 7. 端口与令牌速查

| 项 | 值 |
|---|---|
| broker 监听 | 9240（`HYPIT_BACKEND_PORT`；容器内部） |
| Java→broker | `HYPIT_SIDECAR_BASE_URL`（默认 http://127.0.0.1:9240；容器内 http://hypit-backend:9240） |
| 共享令牌 | `HYPIT_INTERNAL_TOKEN`（≥32 字节；启用时必填；绝不进 runner） |
| Studio 票据密钥 | `HYPIT_STUDIO_TICKET_SECRET`（≥32 字节；仅 broker） |
| nginx Studio 上游 | `HYPIT_STUDIO_UPSTREAM`（空=404 守卫） |
