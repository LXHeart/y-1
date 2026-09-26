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

## 叠加顺序（V12）

```bash
# 基础（无 hypit）：
docker compose -f docker-compose.production.yml config
# + hypit（默认关；--profile hypit 才创建 broker/runner 容器）：
docker compose -f docker-compose.production.yml -f deploy/hypit/compose.production.yml config
# + 完整变体（三 Python 环境/浏览器缓存/模型卷）：
docker compose -f docker-compose.production.yml -f deploy/hypit/compose.production.yml \
               -f deploy/hypit/compose.full.yml config
```

环境模板见 [.env.example](.env.example)。`HYPIT_INTERNAL_TOKEN` 启用时必填且不进 runner。

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

## 备份与恢复

```bash
deploy/hypit/backup.sh <output-dir>        # 维护模式→PG 快照→数据根打包→manifest→恢复副作用
deploy/hypit/restore.sh <backup-dir> <new-data-root>   # 只落空目录；恢复后核对不发新 generation
```

- 备份内容：PG（hypit_*）+ workspace/revisions/results/profiles/程序锁/独立 credentials。
- 凭据目录随数据根走，但**不入仓库**（secret-scan 门禁）。
- 恢复强制 sha256 校验、拒绝非空目标、产出 `restore-report.json`（revision 快照缺口、孤儿
  build 行计数）；overlay 回滚/停用时数据卷与表一律保留。

## 合同测试

`tests/deployment/hypit-compose.contract.test.ts` 与 `hypit-entrypoint.contract.test.ts`
守护以上全部不变量（profile 门禁、隔离键、env 表默认值、nginx 空上游 404、根接线镜像层分发）。
