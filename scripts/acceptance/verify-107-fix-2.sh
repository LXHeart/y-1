#!/usr/bin/env bash
# verify-107-fix-2.sh — 任务书 107-fix-2 分层验证唯一入口（W003，V-07～V-11）。
#
# 用法:
#   bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-01 [选项]
#   bash scripts/acceptance/verify-107-fix-2.sh --stage local|e2e|recovery|all
#
#   --card <C107F2-01…40>   stage=card 必填；仅运行该卡已登记测试与已落地依赖回归
#   --test-filter <substr>  传递给 vitest 的用例名过滤（负向验证零用例拒绝时使用）
#   --help                  本说明
#
# 规则（§12.3/§12.4）:
#   - 未知参数 exit 2；未交付 stage exit 2 并列 NOT_RUN，绝不打印 ALL-GREEN。
#   - 卡级目标用例「实际执行数」为 0（全部被过滤/跳过）时 exit 3 并指出零用例，
#     不能记录 PASS —— 防止空过滤器/全 skip 假绿（F35 的核心教训）。
#   - stdout 与 results.json 记录任务版本/commit/diff 摘要/运行时间/Node 版本、
#     实际发现的 TC、失败/skip/NOT_RUN；exit0 不掩盖 summary 缺项。
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"
ORIGINAL_ARGS=("$@")
source "$REPO_ROOT/scripts/lib/local-stack.sh"

TASK_BOOK="docs/任务书/草场任务书-107-fix-2-Hypit全链路缺陷修复与真实交付验收.md"
TASK_VERSION="107-fix-2 v1.0.0"
# FIX2_ART_BASE 兼容契约（107-fix-3 C107F3-13/W052 消费）：fix3 总入口以
# `FIX2_ART_BASE=<绝对 run stage 目录>` 委托本脚本的 card 层（如 --card C107F2-26/
# C107F2-32），全部证据落入 fix3 run 目录；不设时保持旧缺省路径不变。
# 禁止从 fix3 委托旧 all/local/recovery（可能自起栈）；只允许上面的 card 形态。
ART_BASE="${FIX2_ART_BASE:-test-artifacts/task-107/fix2}"

# ── stage 实现旗标：由各责任卡落地后翻转（0=NOT_RUN，exit 2） ─────────────
# C107F2-39 收口翻转：local（C08）/e2e（C37）/recovery（C38）责任卡均已 VERIFIED，
# all 汇总层随本卡交付；V-11 判定只认各层实际产物，旗标只解除 NOT_RUN 拦截。
STAGE_LOCAL_IMPLEMENTED=1     # C107F2-08（V-08）
STAGE_E2E_IMPLEMENTED=1       # C107F2-37/C107F2-39（V-09）
STAGE_RECOVERY_IMPLEMENTED=1  # C107F2-38（V-10）
STAGE_ALL_IMPLEMENTED=1       # C107F2-39/C107F2-40（V-11）

usage() {
  cat <<'USAGE'
verify-107-fix-2.sh — 107-fix-2 分层验证入口
  --stage card     必须配合 --card C107F2-01…40：运行该卡已登记测试（V-07）
  --stage local    隔离 Docker 栈 + 真实 API 原生成片（V-08，C107F2-08）
  --stage e2e      真实浏览器三引擎（V-09，C107F2-37）
  --stage recovery 故障注入/备份恢复演练（V-10，C107F2-38）
  --stage all      全层汇总（V-11，C107F2-39/40）
  --test-filter <substr>  用例名过滤（vitest -t）；命中 0 实际执行 → exit 3
  --help           本说明
USAGE
}

# ── 参数解析（未知参数 exit 2） ───────────────────────────────────────────
STAGE=""
CARD=""
TEST_FILTER=""
while [ $# -gt 0 ]; do
  case "$1" in
    --stage) STAGE="${2:-}"; shift 2 ;;
    --card) CARD="${2:-}"; shift 2 ;;
    --test-filter) TEST_FILTER="${2:-}"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) printf '未知参数: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done
case "${STAGE}" in
  card|local|e2e|recovery|all) ;;
  "") printf '缺少 --stage\n' >&2; usage >&2; exit 2 ;;
  *) printf '未知 stage: %s\n' "${STAGE}" >&2; usage >&2; exit 2 ;;
esac
if [ "${STAGE}" = "card" ]; then
  if [[ ! "${CARD}" =~ ^C107F2-(0[1-9]|[1-2][0-9]|3[0-9]|40)$ ]]; then
    printf 'stage=card 必须提供 --card C107F2-01…40（收到: %s）\n' "${CARD}" >&2
    exit 2
  fi
fi

# 单个验收流程持锁到验证与收尾结束；C02 的空库切换只重置本次新建资源。
# e2e/recovery/all 不经外层租约：e2e 的 ci-e2e-107 要自管 y1-e2e-local 的 fresh
# 隔离会话（嵌在非 fresh 外层租约下会被「父流程不是 fresh 隔离验收」拒绝——V-09
# 首跑实录）；recovery 的恢复演练自管 y1-hypit-fix2-restore 轮换；all 只读产物
# + 跑契约 vitest（无 Docker）。三者的内部机制各自持锁收尾，不与外层嵌套。
case "$STAGE:$CARD" in
  card:C107F2-02) local_stack_enter "${HYPIT_C02_PROJECT:-y1-hypit-fix2-e2e}" "$REPO_ROOT/scripts/acceptance/verify-107-fix-2.sh" --fresh --cleanup -- "${ORIGINAL_ARGS[@]}" ;;
  card:C107F2-03) local_stack_enter fix2-c03-images "$REPO_ROOT/scripts/acceptance/verify-107-fix-2.sh" --fresh --cleanup -- "${ORIGINAL_ARGS[@]}" ;;
  card:C107F2-04) local_stack_enter "${HYPIT_C04_PROJECT:-fix2-c04-runner}" "$REPO_ROOT/scripts/acceptance/verify-107-fix-2.sh" --fresh --cleanup -- "${ORIGINAL_ARGS[@]}" ;;
  # gradle IT 卡（Testcontainers PG 需真实 Docker）走 --docker 盘点守卫；
  # C107F2-26 为 107-fix-3 V-03 的委托卡（HypitFix2C26IT/HypitVariantIT）。
  card:C107F2-0[5-8]|card:C107F2-26) local_stack_enter y1-hypit-fix2-e2e "$REPO_ROOT/scripts/acceptance/verify-107-fix-2.sh" --docker --cleanup -- "${ORIGINAL_ARGS[@]}" ;;
  e2e:*|recovery:*|all:*) ;;
  *) local_stack_enter y1-hypit-fix2-e2e "$REPO_ROOT/scripts/acceptance/verify-107-fix-2.sh" --cleanup -- "${ORIGINAL_ARGS[@]}" ;;
esac

# ── 公共元数据 ───────────────────────────────────────────────────────────
COMMIT="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
DIFF_SUMMARY="$(git status --short 2>/dev/null | head -20 | tr '\n' ';' | sed 's/;$/;/')"
NODE_VERSION="$(node --version 2>/dev/null || echo unknown)"
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# 卡级注册表：卡号 → 测试规格（可多行多个）+ typecheck 层。
#   vitest:<file>  根 vitest（前端/部署契约）
#   backend:<group> platform-hypit/backend 的 run-tests.mjs 组（Node24.14.1）
#   gradle:<class> intelligence-service 的 IT 类（V-03 形态）
# 由各责任卡落地时登记自己的测试；未登记的卡按 NOT_RUN 处理。
card_registry() {
  case "$1" in
    C107F2-01)
      CARD_TESTS=(vitest:tests/deployment/hypit-fix2-spec.contract.test.ts vitest:tests/deployment/hypit-fix2-c01.contract.test.ts)
      CARD_TYPECHECK=none
      ;;
    C107F2-02)
      CARD_TESTS=(vitest:tests/deployment/hypit-fix2-c02.contract.test.ts vitest:tests/deployment/hypit-fix2-spec.contract.test.ts)
      CARD_TYPECHECK=none
      ;;
    C107F2-03)
      CARD_TESTS=(backend:image-isolation)
      CARD_TYPECHECK=backend
      ;;
    C107F2-04)
      CARD_TESTS=(backend:runner-isolation)
      CARD_TYPECHECK=backend
      ;;
    C107F2-05)
      CARD_TESTS=(gradle:com.grassland.intelligence.hypit.fix2.HypitFix2C05IT)
      CARD_TYPECHECK=gradle
      ;;
    C107F2-06)
      CARD_TESTS=(gradle:com.grassland.intelligence.hypit.fix2.HypitFix2C06IT)
      CARD_TYPECHECK=gradle
      ;;
    C107F2-07)
      CARD_TESTS=(gradle:com.grassland.intelligence.hypit.fix2.HypitFix2C07IT)
      CARD_TYPECHECK=gradle
      ;;
    C107F2-08)
      # 真实 API 原生成片纵向链（V-08 技术入口；spec 前置=隔离栈+seed，由本脚本编排）。
      CARD_TESTS=(playwright:tests/e2e/hypit-fix2-api-render.spec.ts)
      CARD_TYPECHECK=none
      ;;
    C107F2-09)
      # C09：顶层 readiness/登录/禁用/不可用（组件层 + 运行时闸门）；Java 侧
      # capabilities/doctor 接 readiness 需编译验证。
      CARD_TESTS=(vitest:src/views/video-clone/composables/fix2-c09.test.ts)
      CARD_TYPECHECK=gradle
      ;;
    C107F2-10)
      # C10：文件 hash 契约与版本化保存（composable 冻结提交/CAS/契约校验）；
      # Java file GET 契约归一需编译验证。
      CARD_TESTS=(vitest:src/views/video-clone/composables/fix2-c10.test.ts)
      CARD_TYPECHECK=gradle
      ;;
    C107F2-11)
      # C11：工程深链/分页/创建导航/世代隔离（组件层 + scope gate）；
      # Java keyset 分页（Repository/Service/Controller）与契约需编译验证。
      CARD_TESTS=(vitest:src/views/video-clone/composables/fix2-c11.test.ts
        vitest:src/views/video-clone/composables/hypit-api.test.ts)
      CARD_TYPECHECK=gradle
      ;;
    C107F2-12)
      # C12：结果与归档 API-05 契约（items/nextCursor 兼容别名、null 未知值、
      # 按名归档幂等）——HypitFix2C12IT 四组（全形态/终态归档/并发同 mediaId/空态）。
      CARD_TESTS=(gradle:com.grassland.intelligence.hypit.fix2.HypitFix2C12IT)
      CARD_TYPECHECK=gradle
      ;;
    C107F2-13)
      # C13：SSE 事件协议/增量游标/恢复（F24/F06）——IT 四组（envelope+terminal/
      # 10 秒增量节奏/Last-Event-ID 续接/旧 evt 兼容+跨 job reset）；前端 fetch 流
      # 重连语义由 useHypitJobs.test.ts 七组覆盖。
      CARD_TESTS=(gradle:com.grassland.intelligence.hypit.fix2.HypitFix2C13IT
        vitest:src/views/video-clone/composables/useHypitJobs.test.ts)
      CARD_TYPECHECK=gradle
      ;;
    C107F2-14)
      # C14：Agent 工具分发/逐步执行/真实成功判定（F21/RULE-08）——IT 四组
      # （契约=注册表+逐工具真实 handler+类型保真/失败不伪成功/观察再规划+40 截断/
      # scope 单因子零副作用）；含 HypitAgentIT 越权语义迁移回归。
      CARD_TESTS=(gradle:com.grassland.intelligence.hypit.fix2.HypitFix2C14IT
        gradle:com.grassland.intelligence.hypit.agent.HypitAgentIT)
      CARD_TYPECHECK=gradle
      ;;
    C107F2-15)
      # C15：Agent 租约/操作幂等/取消/resume（F23/RULE-09）——IT 四组（90s 租约+15s
      # 续租双 worker 单执行权/planned 接管同幂等键不双副作用/终态 CAS+cancel 优先/
      # waiting_input resume 幂等+新规划轮）；含 agent 域回归（hash canonical 化迁移）。
      CARD_TESTS=(gradle:com.grassland.intelligence.hypit.fix2.HypitFix2C15IT
        gradle:com.grassland.intelligence.hypit.agent.HypitAgentJobIT)
      CARD_TYPECHECK=gradle
      ;;
    C107F2-16)
      # C16：全片参考分析/转写/时间锚点（F22/F06/RULE-10）——IT 四组（12s 三段真实
      # probe/抽帧/转写链+锚点一致可重读/视觉缺失 waiting 不冒充/无声 ABSENT 零转写
      # 不伪造台词+截断 PROVISIONAL/换源 hash 失配 409）；含 clone-plan 域回归。
      CARD_TESTS=(gradle:com.grassland.intelligence.hypit.fix2.HypitFix2C16IT
        gradle:com.grassland.intelligence.hypit.agent.HypitClonePlanIT)
      CARD_TYPECHECK=gradle
      ;;
    C107F2-17)
      # C17：方案转有内容源码与材料需求（F22/F29 写侧）——IT 四组（不同 brief 时长/字幕/
      # 分段/素材实际不同非注释差异/诊断修复 validated+失败稿不覆盖 head/缺绑定 asset
      # WAITING_INPUT 零副作用/越界路径与 shell 整批拒绝）；含 authoring 域回归。
      CARD_TESTS=(gradle:com.grassland.intelligence.hypit.fix2.HypitFix2C17IT
        gradle:com.grassland.intelligence.hypit.agent.HypitAuthoringIT)
      CARD_TYPECHECK=gradle
      ;;
    C107F2-18)
      # C18：分析/方案/生成/重试/取消/授权 UI（F06）——useHypitWorkflow 编排四组
      # （本地计划 POST plan/pricing/build 显示 job/远程 Need 取消零提交+确认一次/
      # cancel 连点合并观察终态/超时重试同 requestId 恢复同 job）；含 video-clone
      # 全域 vitest 回归与 vue-tsc。
      CARD_TESTS=(vitest:src/views/video-clone/composables/fix2-c18.test.ts
        vitest:src/views/video-clone/composables/fix2-c11.test.ts
        vitest:src/views/video-clone/composables/useHypitJobs.test.ts)
      CARD_TYPECHECK=root
      ;;
    C107F2-19)
      # C19：原生 Studio 启动 + HTTP/WS/写回接通（F16/D-08）——Node 集成四组
      # （真实子进程资源200+WS101+原生控件/语义写回经 Java changeset 通道文件+哈希一致
      # 且拒绝零副作用/启动失败不留假会话/子进程环境白名单+代理闸门拒绝裸抓/越权/过期）；
      # Java 侧 V92 会话表迁移 IT（§7.5 五场景）+ 惰性 expired/断言签发/票据核销编译验证。
      CARD_TESTS=(backend:agent-integration
        gradle:com.grassland.intelligence.hypit.fix2.HypitFix2SessionMigrationIT)
      CARD_TYPECHECK=gradle
      ;;
    C107F2-20)
      # C20：Studio 票据/只读/版本复用/撤销生命周期（F19）——Node 集成四组（复用键
      # rev/readOnly 变体新会话/同会话换新票 nonce 单槽替换+CAS 一次核销/readonly
      # HTTP+WS 写面服务端 403 文件零变更/撤销立即拒访问终止子进程+close 幂等）；
      # Java 侧 HypitFix2C20IT 四组（真 PG 复用键/换票槽替换+核销/关闭幂等+sidecar
      # 通知/撤销+惰性过期）+ 编译验证。
      CARD_TESTS=(backend:agent-integration
        gradle:com.grassland.intelligence.hypit.fix2.HypitFix2C20IT)
      CARD_TYPECHECK=gradle
      ;;
    C107F2-21)
      # C21：iframe 安全策略三入口回归（F17）——部署契约四组（URI map 切 SAMEORIGIN+
      # frame-ancestors self 仅 /studio/、默认 DENY/none 三入口不回退/auth_request 固定
      # Edge access 路由+断言头覆盖伪造+内部 token 不透出/ticket 查询串禁入访问日志）
      # + 入口装配回归（80/81 未切换变量+DH 片段互不覆盖）；gradle 编译验证共享文件。
      CARD_TESTS=(vitest:tests/deployment/hypit-fix2-c21.contract.test.ts
        vitest:tests/deployment/hypit-entrypoint.contract.test.ts)
      CARD_TYPECHECK=gradle
      ;;
    C107F2-22)
      # C22：构建绑定不可变版本的真实 Preview 服务（F18）——Node 集成四组（A 快照
      # 不随 head 漂移/授权视频 206+Content-Range 越权 404/缺快照显式失败不回退
      # head/关闭撤销资源且 Build 域零接触）+ studio/media 组回归（preview 契约重写
      # 为不可变快照）；Java preview 服务重写编译验证。
      CARD_TESTS=(backend:agent-integration)
      CARD_TYPECHECK=gradle
      ;;
    C107F2-23)
      # C23：预览跨帧消息/播放状态/资源释放（F20/F18）——fix2-c23 四组（origin=null
      # opaque 不拒绝接收 frame0/错误 source 忽略不改播放状态/NaN 负数未知 type 全忽略
      # 无异常/open 挂起后 close 代际作废不重开+reset 服务端 DELETE 恰一次）+ video-clone
      # 全域回归 + vue-tsc。
      CARD_TESTS=(vitest:src/views/video-clone/composables/fix2-c23.test.ts
        vitest:src/views/video-clone/composables/fix2-c11.test.ts
        vitest:src/views/video-clone/composables/useHypitJobs.test.ts)
      CARD_TYPECHECK=root
      ;;
    C107F2-24)
      # C24：Feedback 批次校验和落盘原子化（F32/§6.11）——fix2-c24 四组（两 add 一次
      # 提交 hash 一致/未知操作整批拒绝字节不变/同 hash 并发恰一批胜 409 携当前 hash/
      # 同 requestId 重放原回执评论数不增）+ studio 组回归（feedback-roundtrip 迁移）；
      # gradle 编译验证共享文件。
      CARD_TESTS=(backend:agent-integration)
      CARD_TYPECHECK=gradle
      ;;
    C107F2-25)
      # C25：按评论做语义源码修改并关联解决状态（F10/F22/§6.11）——HypitFix2C25IT
      # 四组（最小替换 32px 其余结构不变+resolved+映射/无锚点 WAITING_INPUT 零
      # changeset/检查非法 head 评论不动诊断可见/冲突值整批等待不随机覆盖）+
      # HypitReviewIT 回归（迁移至 commentIds+显式锚点语义）；gradle 编译。
      CARD_TESTS=(gradle:com.grassland.intelligence.hypit.fix2.HypitFix2C25IT
        gradle:com.grassland.intelligence.hypit.agent.HypitReviewIT)
      CARD_TYPECHECK=gradle
      ;;
    C107F2-37)
      # C37：真实浏览器贯通参考素材到成片、Studio 与工程包（F35/G2–G5）——
      # hypit-fix2-journey 四组（12s 素材 UI 全链到 MP4 ffprobe 可核验/Studio iframe
      # 无鉴权 CSP 错误+重开/两变体收敛+定向失败 UI 重试/A 导出 B 导入新 owner
      # ready）；完整 ci-e2e 生命周期（三浏览器、受控文本模型 fixture、零 skip）。
      CARD_TESTS=(playwright:tests/e2e/hypit-fix2-journey.spec.ts)
      CARD_TYPECHECK=root
      ;;
    C107F2-38)
      # C38：执行并发、故障注入、重启与完整恢复演练（F23/F26/F27/F32/F33/F34）——
      # hypit-fix2-recovery 四组（双worker执行中kill→租约接管·同operation不重复
      # 收费/fixture接受后断broker网络→恢复重启按原receipt收敛不新提交/两客户端
      # 同head/hash并发保存与反馈→CAS与批次原子/排空备份→新PG卷恢复→浏览器重开
      # hash一致零新generation原栈不受影响）。容器编排只打隔离项目明确容器；
      # Compose 级启停经 hypit-compose.sh 守卫；需要文本 fixture sidecar。
      CARD_TESTS=(playwright:tests/e2e/hypit-fix2-recovery.spec.ts)
      CARD_TYPECHECK=root
      ;;
    C107F2-39)
      # C39：重建分层验收门禁，禁止漏跑和假全绿（F35）——gates 契约四组（隔离证据
      # 副本上跑真实 stage all：缺层/四故障注入/含 skip/本地全绿 LOCAL_PASS 表述）
      # + compose/entrypoint 装配契约（stage 脚本只经守卫入口、full 五层分明、
      # 三入口拓扑不漂移）。本卡 W 只改判定层（脚本/yaml/契约测试），无 TS 源面。
      CARD_TESTS=(vitest:tests/deployment/hypit-fix2-gates.contract.test.ts
        vitest:tests/deployment/hypit-compose.contract.test.ts
        vitest:tests/deployment/hypit-entrypoint.contract.test.ts)
      CARD_TYPECHECK=none
      ;;
    C107F2-40)
      # C40：复核 35 组缺陷闭合并完成任务级集成判定（F01–35）——spec 契约四组
      # （逐 finding 逆向核对无悬空/产物可消费重开/保留 FAIL 时不得 VERIFIED/
      # 上游冻结与白名单复现）。W002 coverage 同步与文档收口由本卡步骤落地。
      CARD_TESTS=(vitest:tests/deployment/hypit-fix2-spec.contract.test.ts)
      CARD_TYPECHECK=none
      ;;
    C107F2-36)
      # C36：工作区双主题、移动端、键盘和全状态验收（§8.1–8.8）——
      # hypit-fix2-c36.spec 四组（两主题×三视口×四阶段截图+零整页横溢+data-theme
      # 真实切换/纯键盘 新建·导出弹窗焦点·Tab 约束·Esc 保护语义·取消后焦点返回/
      # 定向失败注入 列表错误可重试不伪空+上传失败保留选择/390px 长内容主要动作
      # 可达）；chromium 真实 UI（隔离栈 playwright 编排）。
      CARD_TESTS=(playwright:tests/e2e/hypit-fix2-c36.spec.ts)
      CARD_TYPECHECK=root
      ;;
    C107F2-35)
      # C35：会话/资产/任务/导入临时文件生命周期（F08/F18/F19/F31/§7.4 R-LIFECYCLE）——
      # HypitFix2C35IT 四组（删除顺序撤会话→拒新写→取消 job→清独占派生物 deleted/
      # 共享固化媒体零触碰/清理失败保持 deleting 幂等重试/AB 账号隔离 404）+
      # HypitProjectIT（删除契约升级回归）+ HypitAssetIT + HypitFix2C20IT（会话撤销
      # 矩阵不被破坏）回归；gradle 编译（W197 transfer.ts sweeper 已单独 backend tsc）。
      CARD_TESTS=(gradle:com.grassland.intelligence.hypit.fix2.HypitFix2C35IT
        gradle:com.grassland.intelligence.hypit.project.HypitProjectIT
        gradle:com.grassland.intelligence.hypit.asset.HypitAssetIT
        gradle:com.grassland.intelligence.hypit.fix2.HypitFix2C20IT)
      CARD_TYPECHECK=gradle
      ;;
    C107F2-34)
      # C34：恢复目标映射、数据库核验和失败判定（F34/§6.15、§7.5）——
      # hypit-fix2-c34.contract 四组（归档顶层显式映射 restore-a 无旁落/必传 DSN+
      # files-only PARTIAL+SQL 失败非零不冒充/缺 snapshot 类别计数禁止 READY/
      # 零 generation+报告 v2 可审计）；回归 C33 契约；根 tsc。
      CARD_TESTS=(vitest:tests/deployment/hypit-fix2-c34.contract.test.ts
        vitest:tests/deployment/hypit-fix2-c33.contract.test.ts)
      CARD_TYPECHECK=root
      ;;
    C107F2-33)
      # C33：完整备份 PG 与所有持久文件，校验备份清单（F33/§6.15、§7.4）——
      # hypit-fix2-c33.contract 四组（manifest v2 complete+逐 hash 一致+伞内全覆盖/
      # 临时面 omitted+0600+日志无凭据/tar 注入失败非零+无 complete+租约释放/
      # PG 与文件快照同一维护窗口顺序）；回归 spec 契约；根 tsc。
      CARD_TESTS=(vitest:tests/deployment/hypit-fix2-c33.contract.test.ts
        vitest:tests/deployment/hypit-fix2-spec.contract.test.ts)
      CARD_TYPECHECK=root
      ;;
    C107F2-32)
      # C32：维护模式写入栅栏与在途执行排空（F33/§5 RULE-13、§6.15）——
      # fix2-c32 四组（在途 dispatching/acknowledged+原生 build 证据→enter 等真实完成
      # 不即答 drained/并发过屏障：栅栏前入场计入排空+栅栏后 commands/resources/
      # transfers 503+取消豁免/exit 只认自己 leaseId+重启 fail-closed 持久租约/
      # 他人 enter 409 不泄漏 leaseId）+ agent-integration 全组回归 +
      # maintenance.test.ts（107-4 协议升级 leaseId 契约回归）；
      # 107-fix-3 C107F3-03 步骤4：追加 backend:engine 全组（真实 daemon 四类反例
      # TC-F3-03-01 + 维护协议）——engine 任一必需失败即拉低本卡退出码，
      # 无 known-failure/不登记出口（D-03/RULE-015）；backend tsc。
      CARD_TESTS=(backend:agent-integration backend:engine)
      CARD_TYPECHECK=backend
      ;;
    C107F2-31)
      # C31：参考素材上传与跨创作入口交接（F14/F29/§6.14、§8.3）——
      # HypitFix2C31IT 四组（sourceContext 交接真实字节复制 binaryEqualTo+幂等不二次复制/
      # 256MiB 内上传 202→ready probe 落库/超限 413 零字节出站+回执超限拒收+伪装 mp4 422
      # 无 ready 留 failed 审计/他人 mediaId 404 对象存储零读取+同 project+hash 复用）+
      # HypitAssetIT 回归（导入改真实字节后的既有鉴权/上传/工具面）+ HypitProjectIT
      # （sourceContext 归属核验不被触碰）+ HypitTemplateIT 回归；根 tsc + gradle 编译。
      CARD_TESTS=(gradle:com.grassland.intelligence.hypit.fix2.HypitFix2C31IT
        gradle:com.grassland.intelligence.hypit.asset.HypitAssetIT
        gradle:com.grassland.intelligence.hypit.project.HypitProjectIT
        gradle:com.grassland.intelligence.hypit.template.HypitTemplateIT)
      CARD_TYPECHECK=root
      ;;
    C107F2-30)
      # C30：工程包浏览器下载、上传导入与进度反馈（F14/F31/§6.13、§8.3）——
      # HypitFix2C30IT 四组（导出 202 job→download 元数据→/package 真实 zip 可解压
      # manifest/hash 正确/上传导入新 owner 工程 ready/他人 exportId 状态与取流双 404
      # 零字节/上传中断无 ready 假工程+同 requestId 重试收敛+Range 透传）+
      # HypitTemplateIT 回归（导出 job 化不破坏幂等重放）+ agent-integration 组回归
      # （transfer.ts 新模块不破坏 C28 门禁）；backend tsc + gradle 编译。
      CARD_TESTS=(gradle:com.grassland.intelligence.hypit.fix2.HypitFix2C30IT
        gradle:com.grassland.intelligence.hypit.template.HypitTemplateIT
        backend:agent-integration)
      CARD_TYPECHECK=backend
      ;;
    C107F2-29)
      # C29：导入 PG 登记、owner 绑定与跨服务幂等收敛（F31/§6.13、§7.4）——
      # HypitFix2C29IT 四组（owner B 导入 ready+revision 行 A 查无/同 requestId 同包
      # 并发恰一 project/job/revision/broker 崩溃重放复用保留 projectId 完成登记不
      # 二次解包/同 requestId 异包 409 原状不变+失败收敛 provisioning_failed 可见）+
      # HypitTemplateIT 回归（owner 作用域计数修正）；gradle 编译；broker W176
      # newProjectId 接线由 backend typecheck 前置人工核验。
      CARD_TESTS=(gradle:com.grassland.intelligence.hypit.fix2.HypitFix2C29IT
        gradle:com.grassland.intelligence.hypit.template.HypitTemplateIT)
      CARD_TYPECHECK=gradle
      ;;
    C107F2-28)
      # C28：二进制保真工程包与整包验证（F30/§6.13）——fix2-c28 四组（PNG/MP4/
      # 字体 export→import 逐文件 hash 一致无 UTF8 替换/svrun 非法 compile_failed
      # 无 ready 工程/与绝对路径 symlink hardlink 重复 entry 单因子拒绝 staging
      # 外零写入/计数与 4GiB 上限先行 413 流式校验）+ agent-integration 组回归
      # （transactions contentPath 通道不破坏 C24 语义）+ workspace 组回归
      # （C107-20 迁移 TC/F01 路由 TC + 事务 journal 语义）；backend typecheck。
      CARD_TESTS=(backend:agent-integration backend:workspace)
      CARD_TYPECHECK=backend
      ;;
    C107F2-27)
      # C27：变体前端真实取消、重试、授权与状态展示（F13/F28 前端半边）——
      # fix2-c27 四组（一次 POST cancel 服务端回执落项/重试超时重放同 requestId
      # 409 后 attempt 不变 3/远程变体取消授权零 build 确认后恰一次 grant+build/
      # 刷新世代防串写+只落失败项）；fix2-c11 变体列表回归 + c18 授权流回归；
      # vue-tsc（W072/W132 新接线）。
      CARD_TESTS=(vitest:src/views/video-clone/composables/fix2-c27.test.ts
        vitest:src/views/video-clone/composables/fix2-c11.test.ts
        vitest:src/views/video-clone/composables/fix2-c18.test.ts)
      CARD_TYPECHECK=root
      ;;
    C107F2-26)
      # C26：变体生命周期显式映射、重试取消路由与服务端授权（F13/F28/§6.12；
      # 107-fix-3 C107F3-01 补测 TC-F2-26-02）——
      # HypitFix2C26IT 四组（finished+complete+结果就绪→succeeded 中间态观察
      # cancelled 映射/只重试失败项 attempt 恰+1 同键重复状态闸拒 取消只作用指定
      # 项且 build 真实收敛/variant_count=1 并发 prepare 恰一项获准/真实 HTTP
      # retry-cancel 路由：bindToServer 走完整 Controller→owner 闸→Service→PG，
      # DisplayName 同屏携带 TC-F3-01-01～03（动作/隔离/非法状态），Edge 公共
      # 入口穿透证据由 107-fix-3 V-15 另证）+
      # HypitVariantIT 回归（retry/cancelled 扩展不破坏既有状态机）；gradle 编译。
      CARD_TESTS=(gradle:com.grassland.intelligence.hypit.fix2.HypitFix2C26IT
        gradle:com.grassland.intelligence.hypit.variant.HypitVariantIT)
      CARD_TYPECHECK=gradle
      ;;
    *) CARD_TESTS=(); CARD_TYPECHECK=none ;;
  esac
}

# 汇总 results.json（node 写 JSON，避免外部依赖）。
write_results() { # $1=目录 $2=exitCode $3=statusJSON（单行）
  local dir="$1" code="$2" status_json="$3"
  mkdir -p "${dir}"
  node -e '
    const fs = require("node:fs");
    // node -e 的 argv 无脚本名位：argv[1] 起即实参。
    const [dir, code, statusJson, task, commit, diff, started, nodeVersion] = process.argv.slice(1);
    const status = JSON.parse(statusJson);
    const results = {
      taskBook: task, stage: status.stage, card: status.card ?? null,
      exitCode: Number(code), commit, diffSummary: diff,
      startedAt: started, finishedAt: new Date().toISOString(),
      node: nodeVersion, java: null, imageDigest: null,
      tests: status.tests ?? null, tc: status.tc ?? null, notRun: status.notRun ?? [], notes: status.notes ?? [],
    };
    fs.writeFileSync(dir + "/results.json", JSON.stringify(results, null, 2) + "\n");
  ' "${dir}" "${code}" "${status_json}" "${TASK_VERSION}" "${COMMIT}" "${DIFF_SUMMARY}" "${STARTED_AT}" "${NODE_VERSION}"
}

run_not_run() { # $1=stage $2=原因 $3=目录名
  local dir="${ART_BASE}/$3"
  local msg="NOT_RUN stage=$1 原因=$2（未交付 stage 必须退出 2，绝不打印全绿标语）"
  printf '%s\n' "${msg}"
  write_results "${dir}" 2 "$(node -e 'const s=process.argv[1];process.stdout.write(JSON.stringify({stage:s,notRun:[{stage:s,reason:process.argv[2]}]}))' "$1" "$2")"
  exit 2
}

# ── stage=card：V-07 ─────────────────────────────────────────────────────
# 汇总各 runner 的计数（node 内联聚合，避免 bash 算术与 JSON 拼装）。
aggregate_results() { # $1=聚合JSON文件 $2=runner $3=子命令退出码 $4=统计JSON（可能为空）
  node -e '
    const fs = require("node:fs");
    const [file, runner, codeStr, statsJson] = process.argv.slice(1);
    const code = Number(codeStr);
    let stats = {};
    try { stats = JSON.parse(statsJson || "{}"); } catch {}
    let all = {};
    try { all = JSON.parse(fs.readFileSync(file, "utf8")); } catch {}
    all.runners ??= [];
    all.runners.push({
      runner,
      exitCode: code,
      total: stats.total ?? 0, passed: stats.passed ?? 0,
      failed: stats.failed ?? 0, skipped: stats.skipped ?? 0,
      executed: stats.executed ?? 0,
      tc: stats.tc ?? { found: [], failed: [] },
    });
    fs.writeFileSync(file, JSON.stringify(all));
  ' "$1" "$2" "$3" "$4"
}

run_stage_card() {
  local dir="${ART_BASE}/${CARD}"
  mkdir -p "${dir}"
  card_registry "${CARD}"
  if [ "${#CARD_TESTS[@]}" -eq 0 ]; then
    run_not_run "card(${CARD})" "该卡测试尚未登记（责任卡未落地）" "${CARD}"
  fi
  local agg="${dir}/aggregate.json"
  rm -f "${agg}"

  # 按类型分桶。
  local -a vitest_specs=() backend_specs=() gradle_specs=() playwright_specs=() e2e_specs=()
  local spec kind target
  for spec in "${CARD_TESTS[@]}"; do
    kind="${spec%%:*}"; target="${spec#*:}"
    case "${kind}" in
      vitest) vitest_specs+=("${target}") ;;
      backend) backend_specs+=("${target}") ;;
      gradle) gradle_specs+=("${target}") ;;
      playwright) playwright_specs+=("${target}") ;;
      e2e) e2e_specs+=("${target}") ;;
    esac
  done

  # ① vitest（JSON 报告，实际执行数=passed+failed）。
  if [ "${#vitest_specs[@]}" -gt 0 ]; then
    local filter_args=()
    if [ -n "${TEST_FILTER}" ]; then filter_args=(-t "${TEST_FILTER}"); fi
    local vjson="${dir}/vitest.json"
    rm -f "${vjson}"
    printf '[%s] %s vitest: %s%s\n' "${STARTED_AT}" "${CARD}" "${vitest_specs[*]}" \
      "$([ -n "${TEST_FILTER}" ] && printf ' (filter=%s)' "${TEST_FILTER}")"
    local vitest_code=0
    npx vitest run --maxWorkers=1 --no-file-parallelism ${vitest_specs[@]+"${vitest_specs[@]}"} ${filter_args[@]+"${filter_args[@]}"} \
      --reporter=default --reporter=json --outputFile.json="${vjson}" \
      >"${dir}/vitest.log" 2>&1 || vitest_code=$?
    local vstats
    vstats="$(node -e '
      const fs = require("node:fs");
      let d = null;
      try { d = JSON.parse(fs.readFileSync(process.argv[1], "utf8")); } catch {}
      if (!d) { console.log(JSON.stringify({ executed: -1 })); process.exit(0); }
      const executed = (d.numPassedTests ?? 0) + (d.numFailedTests ?? 0);
      const found = new Set(); const tcFailed = [];
      for (const suite of d.testResults ?? []) for (const a of suite.assertionResults ?? []) {
        for (const m of (a.fullName ?? "").matchAll(/TC-F2-\d{2}-\d{2}/g)) found.add(m[0]);
        if (a.status === "failed") for (const m of (a.fullName ?? "").matchAll(/TC-F2-\d{2}-\d{2}/g)) tcFailed.push(m[0]);
      }
      console.log(JSON.stringify({
        executed, total: d.numTotalTests ?? 0, passed: d.numPassedTests ?? 0,
        failed: d.numFailedTests ?? 0, skipped: (d.numPendingTests ?? 0) + (d.numTodoTests ?? 0),
        tc: { found: [...found], failed: [...new Set(tcFailed)] },
      }));
    ' "${vjson}")"
    aggregate_results "${agg}" vitest "${vitest_code}" "${vstats}"
  fi

  # ② backend（Node24.14.1 run-tests.mjs；组级 fail-closed 自带零用例非零）。
  if [ "${#backend_specs[@]}" -gt 0 ]; then
    local group backend_code=0
    for group in "${backend_specs[@]}"; do
      local blog="${dir}/backend-${group}.log"
      printf '[%s] %s backend: TEST_GROUP=%s\n' "${STARTED_AT}" "${CARD}" "${group}"
      ( cd platform-hypit/backend && TEST_GROUP="${group}" npx --yes --package=node@24.14.1 -- node scripts/run-tests.mjs ) >"${blog}" 2>&1 || backend_code=$?
      local bstats
      bstats="$(node -e '
        const fs = require("node:fs");
        const text = fs.readFileSync(process.argv[1], "utf8");
        const pass = (text.match(/^\s*✔/gm) ?? []).length;
        const fail = (text.match(/^\s*✖/gm) ?? []).length;
        const found = new Set(); const tcFailed = [];
        // TC 发现清单（107-fix-3 C107F3-03）：与 W053 TC_PATTERN/gradle 侧同族宽松发现
        // TC-[A-Z0-9]+-nn-nn（C32 现含 backend:engine，其内 TC-F3-03-01 等必须可发现）；
        // 只加宽发现，不改任何退出码判定。
        const tcRe = /TC-[A-Z0-9]+-\d{2}-\d{2}/g;
        for (const m of text.matchAll(tcRe)) found.add(m[0]);
        for (const line of text.split("\n")) if (/^\s*✖/.test(line)) for (const t of line.matchAll(tcRe)) tcFailed.push(t[0]);
        console.log(JSON.stringify({
          executed: pass + fail, total: pass + fail, passed: pass, failed: fail, skipped: 0,
          tc: { found: [...found], failed: [...new Set(tcFailed)] },
        }));
      ' "${blog}")"
      aggregate_results "${agg}" "backend:${group}" "${backend_code}" "${bstats}"
    done
  fi

  # ③ gradle（V-03 形态：JDK25 + 真实 PG/Testcontainers；--rerun-tasks 禁缓存）。
  if [ "${#gradle_specs[@]}" -gt 0 ]; then
    local glog="${dir}/gradle.log" gradle_code=0 cls
    local -a tests_args=()
    for cls in "${gradle_specs[@]}"; do tests_args+=(--tests "${cls}"); done
    printf '[%s] %s gradle: %s\n' "${STARTED_AT}" "${CARD}" "${gradle_specs[*]}"
    ( cd platform-java \
      && bash -c 'source ../scripts/lib/java-runtime.sh; ensure_java_runtime 25 || exit 1; \
          ./gradlew :services:intelligence-service:test '"${tests_args[*]}"' --rerun-tasks --no-daemon --no-parallel --max-workers=1' ) >"${glog}" 2>&1 || gradle_code=$?
    local gstats
    gstats="$(node -e '
      const fs = require("node:fs");
      const path = require("node:path");
      // 从 JUnit XML 汇总（build/test-results/test/*.xml）。
      const dir = path.resolve("platform-java/services/intelligence-service/build/test-results/test");
      let total = 0, failed = 0, skipped = 0;
      const found = new Set(); const tcFailed = [];
      // TC 发现清单（107-fix-3 C107F3-01 步骤4）：与 W053 TC_PATTERN 同族宽松发现
      // TC-[A-Z0-9]+-nn-nn（fix2=TC-F2-nn-nn、fix3=TC-F3-nn-nn）——C26 的
      // HypitFix2C26IT DisplayName 同屏携带 TC-F2-26-02 与 TC-F3-01-01～03，
      // V-03 emit 按全发现核验期望清单；只加宽发现，不改任何退出码判定。
      const tcRe = /TC-[A-Z0-9]+-\d{2}-\d{2}/g;
      try {
        for (const file of fs.readdirSync(dir).filter((f) => f.endsWith(".xml"))) {
          const xml = fs.readFileSync(path.join(dir, file), "utf8");
          for (const m of xml.matchAll(/<testcase name="([^"]+)"/g)) {
            total += 1;
            const name = m[1];
            for (const t of name.matchAll(tcRe)) found.add(t[0]);
          }
          for (const m of xml.matchAll(/<testcase name="([^"]+)"[^>]*>\s*<(failure|error)/g)) {
            failed += 1;
            for (const t of m[1].matchAll(tcRe)) tcFailed.push(t[0]);
          }
          for (const m of xml.matchAll(/<testcase name="([^"]+)"[^>]*>\s*<skipped/g)) skipped += 1;
        }
      } catch {}
      const passed = total - failed - skipped;
      console.log(JSON.stringify({
        executed: passed + failed, total, passed, failed, skipped,
        tc: { found: [...found], failed: [...new Set(tcFailed)] },
      }));
    ')"
    aggregate_results "${agg}" gradle "${gradle_code}" "${gstats}"
  fi

  # ④ playwright（V-08 形态：真实隔离栈 + 合成账号 + 真 API；chromium，API-only）。
  if [ "${#playwright_specs[@]}" -gt 0 ]; then
    local BASE_URL_LOCAL="${BASE_URL:-http://127.0.0.1:18080}"
    # C107F2-37/C107F2-38：journey 与 recovery 卡都需要受控文本模型 fixture
    # （治理台正式路由，容器 sidecar；recovery 的 author 链走同一 fixture）。
    # HYPIT_FIX2_TEXT_FIXTURE=1 时 hypit-compose 叠加 overlay，且 up 清单加入 sidecar。
    local -a extra_up=()
    if printf '%s\n' "${playwright_specs[@]}" | grep -qE "hypit-fix2-(journey|recovery)"; then
      export HYPIT_FIX2_TEXT_FIXTURE=1
      # C107F2-37 修复：fixture token 落盘复用（与 isolated-stack.env 的 E2E_PASSWORD
      # 同法）——每轮随机轮换会与治理台已种凭据（409 复用旧 key）错位 → LLM 401。
      local E2E_PASSWORD_FILE_PRE="test-artifacts/task-107/fix2/isolated-stack.env"
      local persisted_token=""
      if [ -f "${E2E_PASSWORD_FILE_PRE}" ]; then
        persisted_token="$(grep -E '^HYPIT_FIX2_PROVIDER_TOKEN=' "${E2E_PASSWORD_FILE_PRE}" | head -1 | cut -d= -f2- | tr -d '\"')"
      fi
      if [ -n "${persisted_token}" ]; then
        export HYPIT_FIX2_PROVIDER_TOKEN="${HYPIT_FIX2_PROVIDER_TOKEN:-${persisted_token}}"
      else
        # 默认钉成与 fixtures/hypit-fix2.ts 同源的静态值（存量凭据同 key）：fixture
        # 只在隔离栈 intelligence 网络命名空间的 loopback 可达，测试替身不承载真秘密。
        export HYPIT_FIX2_PROVIDER_TOKEN="${HYPIT_FIX2_PROVIDER_TOKEN:-fix2-journey-fixture-key}"
        if [ -d "$(dirname "${E2E_PASSWORD_FILE_PRE}")" ]; then
          printf 'HYPIT_FIX2_PROVIDER_TOKEN=%s\n' "${HYPIT_FIX2_PROVIDER_TOKEN}" >>"${E2E_PASSWORD_FILE_PRE}"
          chmod 600 "${E2E_PASSWORD_FILE_PRE}" 2>/dev/null || true
        fi
      fi
      extra_up+=(hypit-fix2-text-provider)
    fi
    # HTTP 200 不能证明项目归属；始终经过守卫核对标签与依赖。
    # 当前 Hypit 原生成片用例不需要 DH；frontend 的真实 Java 依赖由守卫展开。
    # --disable-dh：隔离组合显式不含 DH overlay（孤儿 dh 容器标签会让预检拦截，
    # 走守卫的显式停用确认通道，受影响模块由守卫打印）。
    # redis：identity 断言 replay 防护的强依赖（登录链路必需，compose 注释明示），
    # 但 depends_on 图未声明——不进守卫展开集，须显式点名，否则按清单外服务拦截。
    # round-8 实录：trust-service 冷重建 JVM+Kafka join 慢于 up --wait 窗口被误判
    # unhealthy（容器随后自行 healthy）。首次失败等 60s 重试一次：二次 up 是完整
    # --wait 重核，不是放宽判定；再失败才判启动失败。
    bash scripts/acceptance/hypit-compose.sh --test --enable-hypit --disable-dh up \
      frontend hypit-backend hypit-author-runner redis ${extra_up[@]+"${extra_up[@]}"} \
      >"${dir}/stack-up.log" 2>&1 || {
        sleep 60
        bash scripts/acceptance/hypit-compose.sh --test --enable-hypit --disable-dh up \
          frontend hypit-backend hypit-author-runner redis ${extra_up[@]+"${extra_up[@]}"} \
          >>"${dir}/stack-up.log" 2>&1 || { printf 'FAIL 隔离栈启动失败（%s/stack-up.log）\n' "${dir}"; exit 1; }
      }
    # C107F2-08：hypit-backend 无 compose healthcheck（up --wait 视 started 为
    # Healthy），tsx 冷编译要数分钟才监听 9240——provision 立即 connect refused
    # （一次失败即终态）。up 后从 intelligence 容器内探测 sidecar healthz 直到
    # 真正可用，覆盖冷编译与 JVM DNS/连接池收敛两个窗口。
    local sidecar_ok=0
    for _ in $(seq 1 66); do
      if docker exec y1-hypit-fix2-e2e-intelligence-service-1 \
          wget -q -O /dev/null --timeout=5 http://hypit-backend:9240/healthz 2>/dev/null; then
        sidecar_ok=1; break
      fi
      sleep 10
    done
    [ "${sidecar_ok}" -eq 1 ] || { printf 'FAIL hypit-backend healthz 未在探测窗口内可用\n'; exit 1; }
    bash scripts/acceptance/hypit-compose.sh --test seed-accounts >"${dir}/seed.log" 2>&1 \
      || { printf 'FAIL seed-accounts 失败\n'; exit 1; }
    # spec 登录须与栈 seed 同源口令（一次性随机值在 isolated-stack.env；不回显）。
    local E2E_PASSWORD_FILE="test-artifacts/task-107/fix2/isolated-stack.env"
    if [ -f "${E2E_PASSWORD_FILE}" ]; then
      E2E_PASSWORD="$(grep -E '^E2E_PASSWORD=' "${E2E_PASSWORD_FILE}" | head -1 | cut -d= -f2- | tr -d '\"')";
      export E2E_PASSWORD
    fi
    # C107F2-37/C107F2-38 修复：清场前几轮遗留的非终态 job（全部 kind）。worker
    # 周期对头部毒 job（素材已删/上轮半途）失败重试会饿死本轮新 job（claim 按序，
    # 变体构建/import 等同受累——round-7 实录 variant 卡 draft）。只终态化本轮
    # 开始前的存量，不触碰即将新建的 job。
    docker exec y1-hypit-fix2-e2e-postgres-local-1 sh -c \
      'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "update hypit_job set state='"'"'failed'"'"', phase='"'"'error'"'"', error_message=coalesce(error_message,'"'"''"'"') || '"'"'; stale from a previous gate round'"'"' where state in ('"'"'running'"'"','"'"'queued'"'"')"' \
      >"${dir}/stale-jobs.log" 2>&1 || true
    local junit_xml="${dir}/playwright-junit.xml" pw_code=0
    printf '[%s] %s playwright: %s\n' "${STARTED_AT}" "${CARD}" "${playwright_specs[*]}"
    local -a engines=()
    for e in ${E2E_ENGINES:-chromium}; do engines+=(--project="${e}"); done
    BASE_URL="${BASE_URL_LOCAL}" npx playwright test ${playwright_specs[@]+"${playwright_specs[@]}"} \
      ${engines[@]+"${engines[@]}"} \
      >"${dir}/playwright.log" 2>&1 || pw_code=$?
    [ -f test-artifacts/playwright-results.xml ] && mv test-artifacts/playwright-results.xml "${junit_xml}"
    local pstats
    pstats="$(node -e '
      const fs = require("node:fs");
      try {
        const xml = fs.readFileSync(process.argv[1], "utf8");
        // 按 testcase 元素切块判定：playwright junit 的 <failure> 前还有 <system-out>
        // （附件清单），「开标签紧邻 failure」式正则会全部漏检（round-7 实录：
        // 4 全败被记 passed=4）。块内任意位置出现 failure/error 即判败。
        const chunks = xml.split(/<\/testcase>/);
        const cases = [], fails = [];
        for (const chunk of chunks) {
          const m = chunk.match(/<testcase[^>]*\sname="([^"]*)"/);
          if (!m) continue;
          cases.push(m[1]);
          if (/<failure[\s>]|<error[\s>]/.test(chunk)) fails.push(m[1]);
        }
        const found = new Set(), tcFailed = new Set();
        for (const n of cases) for (const m of n.matchAll(/TC-F2-\d{2}-\d{2}/g)) found.add(m[0]);
        for (const n of fails) for (const m of n.matchAll(/TC-F2-\d{2}-\d{2}/g)) tcFailed.add(m[0]);
        console.log(JSON.stringify({
          executed: cases.length, total: cases.length, passed: cases.length - fails.length,
          failed: fails.length, skipped: 0, tc: { found: [...found], failed: [...tcFailed] },
        }));
      } catch { console.log(JSON.stringify({ executed: -1 })); }
    ' "${junit_xml}")"
    aggregate_results "${agg}" playwright "${pw_code}" "${pstats}"
  fi

  # typecheck（按卡登记的层）。
  local typecheck_code=0
  case "${CARD_TYPECHECK}" in
    root) npm run typecheck >"${dir}/typecheck.log" 2>&1 || typecheck_code=$? ;;
    backend) ( cd platform-hypit/backend && npm run typecheck ) >"${dir}/typecheck.log" 2>&1 || typecheck_code=$? ;;
    gradle)
      ( cd platform-java && bash -c 'source ../scripts/lib/java-runtime.sh; ensure_java_runtime 25 || exit 1; ./gradlew :services:intelligence-service:compileJava :services:intelligence-service:compileTestJava --no-daemon --no-parallel --max-workers=1 -q' ) >"${dir}/typecheck.log" 2>&1 || typecheck_code=$? ;;
  esac

  # 聚合判定：实际执行数为 0 → 零用例（exit 3）；任一 runner/typecheck 非零 → exit 1。
  local final
  final="$(node -e '
    const fs = require("node:fs");
    const agg = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
    const tests = { total: 0, passed: 0, failed: 0, skipped: 0, executed: 0 };
    const tcFound = new Set(); const tcFailed = new Set(); const runners = [];
    for (const r of agg.runners ?? []) {
      tests.total += r.total; tests.passed += r.passed; tests.failed += r.failed;
      tests.skipped += r.skipped; tests.executed += r.executed;
      for (const t of r.tc?.found ?? []) tcFound.add(t);
      for (const t of r.tc?.failed ?? []) tcFailed.add(t);
      runners.push({ runner: r.runner, exitCode: r.exitCode });
    }
    const anyFail = (agg.runners ?? []).some((r) => r.exitCode !== 0);
    process.stdout.write(JSON.stringify({ tests, tc: { found: [...tcFound], failed: [...tcFailed] }, runners, anyFail }));
  ' "${agg}")"
  local executed total passed failed skipped any_fail
  executed="$(node -e 'console.log(JSON.parse(process.argv[1]).tests.executed)' "${final}")"
  total="$(node -e 'console.log(JSON.parse(process.argv[1]).tests.total)' "${final}")"
  passed="$(node -e 'console.log(JSON.parse(process.argv[1]).tests.passed)' "${final}")"
  failed="$(node -e 'console.log(JSON.parse(process.argv[1]).tests.failed)' "${final}")"
  skipped="$(node -e 'console.log(JSON.parse(process.argv[1]).tests.skipped)' "${final}")"
  any_fail="$(node -e 'console.log(JSON.parse(process.argv[1]).anyFail)' "${final}")"

  if [ "${executed}" -lt 0 ]; then
    printf 'FAIL %s：测试报告解析失败（不能以无报告判绿）\n' "${CARD}"
    exit_code=4
  elif [ "${executed}" -eq 0 ]; then
    printf 'FAIL %s：零用例实际执行（total=%s passed=%s failed=%s skipped=%s）——过滤器未命中或全部 skip，不能记录 PASS\n' \
      "${CARD}" "${total}" "${passed}" "${failed}" "${skipped}"
    exit_code=3
  else
    exit_code=0
    [ "${any_fail}" = "true" ] && exit_code=1
    [ "${typecheck_code}" -ne 0 ] && exit_code=1
    if [ "${exit_code}" -eq 0 ]; then
      printf 'PASS %s：executed=%s passed=%s failed=%s skipped=%s tcFound=%s\n' \
        "${CARD}" "${executed}" "${passed}" "${failed}" "${skipped}" \
        "$(node -e 'console.log(JSON.parse(process.argv[1]).tc.found.length)' "${final}")"
    else
      printf 'FAIL %s：存在非零 runner 或 typecheck（vitest/backend/gradle 详情见 %s）\n' "${CARD}" "${dir}"
    fi
  fi
  local status_final
  status_final="$(node -e '
    const d = JSON.parse(process.argv[1]);
    process.stdout.write(JSON.stringify({ stage: "card", card: process.argv[2], tests: d.tests, tc: d.tc, runners: d.runners }));
  ' "${final}" "${CARD}")"
  write_results "${dir}" "${exit_code}" "${status_final}"
  exit "${exit_code}"
}

# ── stage=local/e2e/recovery/all ─────────────────────────────────────────
run_stage_local() {
  [ "${STAGE_LOCAL_IMPLEMENTED}" -eq 1 ] || run_not_run "local" "等待 C107F2-08 落地隔离栈+真实渲染链" "local"
  bash scripts/acceptance/stages/107-fix-2-local.sh
}
run_stage_e2e() {
  [ "${STAGE_E2E_IMPLEMENTED}" -eq 1 ] || run_not_run "e2e" "等待 C107F2-37 落地真实浏览器三引擎入口" "e2e"
  bash scripts/acceptance/stages/107-fix-2-e2e.sh
}
run_stage_recovery() {
  [ "${STAGE_RECOVERY_IMPLEMENTED}" -eq 1 ] || run_not_run "recovery" "等待 C107F2-38 落地故障注入/备份恢复演练" "recovery"
  bash scripts/acceptance/stages/107-fix-2-recovery.sh
}
run_stage_all() {
  [ "${STAGE_ALL_IMPLEMENTED}" -eq 1 ] || run_not_run "all" "等待 C107F2-39/40 收口（local/e2e/recovery 全部交付后才可 all）" "all"
  bash scripts/acceptance/stages/107-fix-2-all.sh
}

case "${STAGE}" in
  card) run_stage_card ;;
  local) run_stage_local ;;
  e2e) run_stage_e2e ;;
  recovery) run_stage_recovery ;;
  all) run_stage_all ;;
esac
