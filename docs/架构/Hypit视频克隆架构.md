# Hypit 视频克隆架构

> 任务书 #107-1～#107-3 收口文档（C107-24）。规约来源：contracts/hypit-api.v1.json、
> contracts/hypit-clone-plan.v1.json、contracts/hypit-coverage.v1.json；K03/K05/K06/K09/K10.4/K13。

## 1. 能力定位

视频克隆 = 把一段参考视频复刻为可编辑的多文件作品（SVML 场景语言 + SVS 样式 + SVR run 记录），
创作者在网页完成「参考分析 → 克隆方案 → 多文件生成 → 审片修改 → 批量变体 → 导出/归档」全流程。
引擎本体来自上游 Hypit（vendor，只读），y-1 侧做四层集成：

```
┌─ 浏览器 ──────────────────────────────────────────────────────────┐
│ AI 创作中心（ai.html）                                             │
│  ├─ /video-clone 工作区（src/views/video-clone，C107-21）          │
│  └─ 完整 Studio（受信应用，/studio/<sessionId>/ 票据路径，C107-12） │
└──────┬───────────────────────────────────────────────────────────┘
       │ /api/hypit/*（edge fail-closed 旗标名单，K09）
┌──────▼───────────────────────────────────────────────────────────┐
│ edge-bff：59 端点中 HYPIT 面逐旗映射（默认全关，capabilities 例外）│
└──────┬───────────────────────────────────────────────────────────┘
       │
┌──────▼───────────────────────────────────────────────────────────┐
│ intelligence-service（Java/WebFlux，R-UI/R-JAVA 边界内）           │
│  hypit 包：project/command/job/revision/changeset/variant/        │
│  template/build/agent/asset/api——幂等命令域 + 持久任务 + 计量结算 │
└──────┬───────────────────────────────────────────────────────────┘
       │ sidecar 命令面（mTLS/内网 token，HYPIT_INTERNAL_TOKEN）
┌──────▼───────────────────────────────────────────────────────────┐
│ hypit-backend（可信 Node broker，platform-hypit/backend）          │
│  命令域 dispatcher + bridge.sqlite 幂等 + workspace journal +      │
│  runner 槽管理 + results/results 复用 + Studio/预览会话            │
└──────┬───────────────────────────────────────────────────────────┐
       │ Unix socket（唯一通道，K10.4）
┌──────▼───────────────────────────────────────────────────────────┐
│ hypit-author-runner：作者代码执行槽                                │
│  network_mode:none / uid 10001 / read_only / cap_drop ALL /       │
│  no-new-privileges / 配额 tmpfs；不持 Java 密钥与宿主私有文件      │
└───────────────────────────────────────────────────────────────────┘
```

## 2. 关键机制

- **幂等命令域（K06.1）**：`hypit_command` 以 (account, action, requestId) 幂等键 +
  canonical payload hash；同 requestId 同 body 重放返回同结果，不同 body 409。
  事务内落库、事务外调 sidecar，补偿状态机（provisioning_failed 可重试收敛）。
- **工程文件事务（K06.3）**：changeset save/validated 双语义；validated 在 broker
  临时副本上真实 check，未过不移动 head；apply 以 baseRevision CAS，冲突 409 草稿保留。
- **经济链**：prepareRealtimeExecution 窄入口 + grant Redis GETDEL 原子单次 +
  streamMeteredMessages 计量（include_usage、[DONE] 不吞 usage）+ 核对 worker 只重放结算。
- **变体**：`hypit_variant` 独立 runFile（runs/variants/variant-N.svrun），axes 真交叉积，
  attempt 递增局部重做；批次部分失败不清空（CAS 状态机 draft→planned→queued→running→终态）。
- **模板与工程包**：内置三模板（ranking-tier/caption-motion/deck-stack）真实渲染；工程包
  导出（manifest + secret 扫描拒绝）与导入（五重门禁：staging contain/manifest/大小/lstat
  拒 symlink/selectedRun 存在）。
- **自定义组件**：`packages/<name>/`（manifest + hostFacets + provider）；staged 构建逐包
  symlink（@hypit/hypit→G 根）；provider 走 permit 门控（无授权零 HTTP）。
- **runner 隔离（K10.4）**：恶意组件（读宿主路径/token env/连 Java internal/写 Distribution）
  全部被拒（`B/tests/engine/runner-isolation.test.ts` 真实用例），合法 typed Need 经 broker 成功。

## 3. URL 与入口（C107-22）

- AI 壳路由：`/video-clone`（列表）、`/video-clone/:projectId`（深链）、`/hypit/*` 兼容重定向。
- 视频分析页「用作克隆参考」：只带稳定 ai_run 分析 id（`sourceKind=analysis&sourceId=<uuid>`）；
  临时媒体（解析代理 URL）永不直传——服务端 media 引用只接受 active（已固化）且本人，
  他人/不存在一律 404（无存在性 oracle），未固化 409 `hypit_source_not_permanent`。
- query 里的 label 只做标题预填（展示字段），不进创建载荷、不参与权限判定。
- 用户端壳（index.html）无 video-clone 路由：交接组件 resolve 落空时如实提示，
  绝不跳用户端 index 路径。

## 4. 代码地图

| 层 | 位置 |
|---|---|
| 引擎（vendor 只读） | `platform-hypit/upstream`（0.2.13，commit 2c320059 固定） |
| 生成引擎 G | `platform-hypit/.generated/hypit`（build-107-engine.sh 产物 + 补丁） |
| broker | `platform-hypit/backend` |
| Java 集成 | `platform-java/services/intelligence-service/.../hypit/` |
| 前端工作区 | `src/views/video-clone/`（13 面板 + 8 composable） |
| AI 入口 | `src/ai/router.ts`、`src/views/ai-center/components/VideoCloneEntry.vue` |
| 交接 | `src/views/video/composables/useCloneReferenceTransfer.ts` + `components/CloneReferenceHandoff.vue` |
| 部署 | `deploy/hypit/`（Dockerfile×2、compose production/full/test/runner、nginx、backup/restore） |
| 验收 | `scripts/acceptance/{verify-107-upstream,build-107-engine,ci-e2e-107,verify-107-full,verify-107-live}.sh` |
| 证据契约 | `contracts/hypit-coverage.v1.json`（24 卡逐卡 TC/门禁/证据） |

## 5. 状态与边界（2026-09-26）

- 本地/契约/真渲染（无 key 链、本地模板、真实浏览器渲染 MP4）：**已验证**（V04/V07 全绿）。
- 真实第三方渲染/真实模型/实机（iOS+Android）：**externalValidation=NOT_RUN/BLOCKED**
  ——缺运营者凭据、预算授权与真实价表；入口见 `scripts/acceptance/verify-107-live.sh`。
- 详见 [Hypit视频克隆运行手册](Hypit视频克隆运行手册.md) 与
  [Hypit第三方源码与资源说明](Hypit第三方源码与资源说明.md)。
