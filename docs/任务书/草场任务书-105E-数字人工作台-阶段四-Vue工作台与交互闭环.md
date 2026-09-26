# 草场 #105E：数字人工作台 阶段四-Vue工作台与交互闭环

| 项目 | 内容 |
| --- | --- |
| 版本/日期 | 2.1 / 2026-09-22 |
| 文档状态 | IMPLEMENTED（2026-09-23）：v2.1规格；各卡按 AUTO_CHAIN 落地并实跑 TC/V；真实第三方渲染/实机仍单独门禁（REAL_NOT_RUN）；API16 媒体桥 503 缺口随 105fix-1 C105X-03 接通 |
| 实施/用例 | IMPLEMENTED / 本阶段 TC 全 PASS（本地真实 DB/容器/浏览器，证据 test-artifacts/task-105/E/）；真实 provider 项 NOT_RUN |
| 卡/用例 | 6张任务卡，24组Given/When/Then用例；组内边界需参数化 |
| 基线 | 草场f2976aaeb329ee17e9e08a8997bffaabde78d3ac + 当前#103/#104既有修改；OpenTalking固定8c739a5a6f114daf71aeace832a668c3ad60f536 |
| 前置 | 105D Fake实时集成通过；105B类型/API可用；#104账号缓存与HTTP错误修复已验证 |
| 方式 | AUTO_CHAIN：卡级通过自动进入本阶段下一卡；不启子代理，不自动越到未指定的下一本或生产开放 |
| 入口 | [#105总纲](草场任务书-105-数字人工作台开发计划.md) · [PRD](../产品/数字人工作台需求文档.md) · [共享契约](草场任务书-105-数字人工作台共享契约.md) |

## 0. 开始与完成协议

本书供编码模型直接执行。先读本节、§9、§13、§14，再读当前卡列明的共享K节、源码和测试。CARD PASS才推进；已有真实服务授权在同范围内持续有效，不逐卡重复申请。普通本地依赖、Fake、合成数据库与浏览器验证可直接进行。
先保存`git status --short`和当前diff；不得清空工作区或覆盖已有#103/#104修改。行号漂移以本卡真实符号定位；需要改变业务契约/归属/资金规则才停止受影响卡，不因发现未提交文件而全部停工。所有新增接口都应拒绝未知字段，无密码/密钥进入证据。
完成必须同时有实现、非零用例、卡级命令退出0、适用边界/视觉检查、范围检查和对话交接。NOT_RUN不是PASS；真实条件不足只阻塞相应real/beta门禁，保留Fake可继续的明确路径。

## 1. 本阶段目标与范围

按现有产品设计规范交付可操作、可释放、可保存字幕的个人工作台，所有状态由服务端事实驱动。

| 需求 | 负责卡 | 验收组 |
| --- | --- | --- |
| DH-R01 | C105E-01、C105E-06 | TC105E-01-01～TC105E-01-04、TC105E-06-01～TC105E-06-04 |
| DH-R02 | C105E-01、C105E-06 | TC105E-01-01～TC105E-01-04、TC105E-06-01～TC105E-06-04 |
| DH-R03 | C105E-02 | TC105E-02-01～TC105E-02-04 |
| DH-R04 | C105E-02 | TC105E-02-01～TC105E-02-04 |
| DH-R05 | C105E-02 | TC105E-02-01～TC105E-02-04 |
| DH-R06 | C105E-02 | TC105E-02-01～TC105E-02-04 |
| DH-R07 | C105E-03、C105E-04、C105E-06 | TC105E-03-01～TC105E-03-04、TC105E-04-01～TC105E-04-04、TC105E-06-01～TC105E-06-04 |
| DH-R08 | C105E-04、C105E-06 | TC105E-04-01～TC105E-04-04、TC105E-06-01～TC105E-06-04 |
| DH-R09 | C105E-03、C105E-06 | TC105E-03-01～TC105E-03-04、TC105E-06-01～TC105E-06-04 |
| DH-R10 | C105E-03、C105E-04、C105E-06 | TC105E-03-01～TC105E-03-04、TC105E-04-01～TC105E-04-04、TC105E-06-01～TC105E-06-04 |
| DH-R11 | C105E-03、C105E-05、C105E-06 | TC105E-03-01～TC105E-03-04、TC105E-05-01～TC105E-05-04、TC105E-06-01～TC105E-06-04 |
| DH-R12 | C105E-05、C105E-06 | TC105E-05-01～TC105E-05-04、TC105E-06-01～TC105E-06-04 |
| DH-R15 | C105E-03、C105E-05 | TC105E-03-01～TC105E-03-04、TC105E-05-01～TC105E-05-04 |
| DH-R19 | C105E-01、C105E-02、C105E-04、C105E-05、C105E-06 | TC105E-01-01～TC105E-01-04、TC105E-02-01～TC105E-02-04、TC105E-04-01～TC105E-04-04、TC105E-05-01～TC105E-05-04、TC105E-06-01～TC105E-06-04 |
| DH-R20 | C105E-01 | TC105E-01-01～TC105E-01-04 |

范围外：组织共享、知识库/记忆、对外客服/直播、克隆真人声音、摄像头驱动、任意模型URL、自动交易工具；不顺手改文章/视频工具或前序缺陷。未被本卡白名单列出的文件一律只读。

## 2. 仓库上下文与只读基线

前端Vue/TypeScript，AI独立入口`src/ai/router.ts`及`AiAppLayout.vue`；治理台`src/ops/admin/adminTabs.ts`；Java控制域在intelligence-service，Edge转发；Python实时域为本系列新增目录。现有鉴权、预算、媒体、生命周期只作定点复用。
必读根AGENTS.md与`docs/架构/目录结构.md`。涉及用户/AI UI读根DESIGN.md，治理UI读src/ops/DESIGN.md，共享组件默认根规范。每卡源码摘录是2026-09-21快照，仅作定位；真实实现优先核对签名和上下文。

## 3. 已定技术决策

> 2026-09-22 v2.1修订：K14是模型来源与扩展的统一约束。本地指应用开发、媒体处理和Fake测试，不包括任何模型推理服务；上游源码中的local后端仅是研究事实，不是实施选项。A旧Fake记录保留，v2.1修订复验已通过，可按本阶段前置推进。

Java拥有owner/会话/费用/清理事实；Python持有每会话Binding与媒体，无长期供应商key；Vue只有同源控制资格。首期STT为按键提交后内存整段转写；LLM采用受控计量流；TTS采用24000Hz PCM桥且沿既有平台资助模式；RTC只下行音视频。打断关闭旧peer并重建，租约独立回收。
全部模型均来自第三方服务，通过现有模型控制面使用与扩展；包括STT、LLM、TTS、数字人渲染及涉及模型的形象预处理，禁止本地或自托管模型部署。新增渲染能力沿现有模型/凭据/受信端点/价表链路扩展，不另建模型配置体系，不用env或renderer-profile保存供应商地址、模型名及key。Fake仅用于隔离测试，全链外网请求为0。详见共享K14；配置默认关闭，S1内测、S2个人试点分别验收。

## 4. 目标行为与状态

全局状态迁移见共享K04；本阶段只实现各卡明确触点，不增同义state。session、turn、invocation、settlement、recording、cleanup互相独立：会话ended不表示费用已结或文件已删。失联回收≤45秒、正常结束P95≤5秒，都是待实现/实测目标。

## 5. 业务不变量

首期owner固定个人，orgId不由浏览器提交。账号+epoch变化使旧异步结果无效。每动作requestId创建一次；同键异payload409，同键重放先owner再receipt。费用unknown不填0、不自动重放provider。原始mic不入磁盘、素材库、日志；未同意文本仅有界易失缓冲。

## 6. 接口、字段与内部签名

公开API01～40见共享K03，内部15端点与NDJSON见K07.1～K07.3，管理6端点见K10。金额单位cents、时长ms、UTC时间、JS安全整数、可null/必填及错误码见K01。类型不得用any/无约束map逃避合同；当前卡精确签名见§11。

## 7. 数据、事务与迁移

共享K05是DDL合同，B核心迁移一次创建B/C/D/G核心表；F另建媒体表。执行时按intelligence最大Flyway整数+1替换唯一NEW_DYNAMIC路径中的NEXT，先记录实际文件名，禁止修改V86/V87及其他历史SQL。锁顺序account gate→session→turn/recording→invocation/operation→media；不持事务等待网络。

## 8. UI与用户可观察状态

本阶段涉及UI。共享K11固定布局/组件与设备行为；只用现有token及gl-field/gl-btn-primary/EmptyState/GlModal。新增token必须明暗成对且先补定义（若本卡无style.css写权限，优先现有token；确需新增须修订本卡白名单）。每页loading/empty/error/submitting/disabled/result齐全；1440×900和390×844×明暗截图实际打开检查，另320px、200%缩放、键盘focus。Vue≤800行，composable承载业务，URL独立。

## 9. 写入白名单与禁止项

下面是阶段全局上限；每卡仍只可改其§11表内文件，取两者交集。v2.1新增的§9.1修订表同时补充阶段与指定卡白名单，按表中责任卡执行；其它卡不能借用。NEW仅首创，MODIFY_AFTER表明前卡已创建，不得重建覆盖。只读锚点不是写权限。测试产物可写test-artifacts/task-105/E及卡内指定`test-artifacts/task-105/`子目录（Git忽略），不能放根目录或提交用户内容。

| 完整仓库相对路径 | 动作 | 用途 |
| --- | --- | --- |
| `src/ai/router.ts` | MODIFY | 新增digital-human lazy route，不扩四能力枚举 |
| `src/ai/AiAppLayout.vue` | MODIFY | 只装配导航子组件与既有login事件 |
| `src/ai/components/AiWorkspaceNavigation.vue` | NEW | 创作中心/数字人真实导航 |
| `src/ai/components/AiWorkspaceNavigation.test.ts` | NEW | 模式/可见/键盘导航 |
| `src/views/digital-human/DigitalHumanWorkbench.vue` | NEW | 只组合composables/子组件 |
| `src/views/digital-human/useDigitalHumanUrlState.ts` | NEW | profileId/sessionId/view公开ID状态 |
| `src/views/digital-human/useDigitalHumanUrlState.test.ts` | NEW | 刷新/非法ID/查询净化 |
| `src/style.css` | MODIFY | 复用现有token/gl类；必要数字人布局与新增token明暗成对，不重写全局主题 |
| `src/views/digital-human/components/DigitalHumanProfileForm.vue` | NEW | 字段/兼容/版本冲突 |
| `src/views/digital-human/components/DigitalHumanStartDialog.vue` | NEW | 来源/限制/费用/保存同意 |
| `src/views/digital-human/components/DigitalHumanAvatarPicker.vue` | NEW | 可用预设与失效说明 |
| `src/views/digital-human/composables/useDigitalHumanProfiles.ts` | NEW | 取数、保存、试听、preflight |
| `src/views/digital-human/composables/useDigitalHumanProfiles.test.ts` | NEW | 失败保输入/幂等/换号 |
| `src/views/digital-human/components/DigitalHumanProfileForm.test.ts` | NEW | 输入法/长文本/错误 |
| `src/views/digital-human/composables/useDigitalHumanSession.ts` | NEW | create/pause/resume/end+epoch |
| `src/views/digital-human/composables/useDigitalHumanEvents.ts` | NEW | 逐块事件/快照/重连 |
| `src/views/digital-human/composables/useDigitalHumanMedia.ts` | NEW | recvonly完整ICE/media reset |
| `src/views/digital-human/components/DigitalHumanStage.vue` | NEW | 状态/媒体错误/AI标识 |
| `src/views/digital-human/components/DigitalHumanUsage.vue` | NEW | confirmed/pending/来源 |
| `src/views/digital-human/composables/useDigitalHumanSession.test.ts` | NEW | 定时器/失活/旧票 |
| `src/views/digital-human/composables/useDigitalHumanEvents.test.ts` | NEW | 跨chunk/seq/内容墓碑 |
| `src/views/digital-human/composables/useDigitalHumanMedia.test.ts` | NEW | track/peer/自动播放 |
| `src/views/digital-human/composables/useDigitalHumanMicrophone.ts` | NEW | 显式权限、worklet/WS/stop |
| `src/views/digital-human/audio/pcm-worklet.ts` | NEW | 连续重采样与int16编码 |
| `src/views/digital-human/audio/pcm-resampler.ts` | NEW | 纯重采样可测算法 |
| `src/views/digital-human/components/DigitalHumanComposer.vue` | NEW | Enter/IME/状态与错误 |
| `src/views/digital-human/composables/useDigitalHumanMicrophone.test.ts` | NEW | 迟到权限/close/背压 |
| `src/views/digital-human/audio/pcm-resampler.test.ts` | NEW | 44100/48000→16000保持时长与频率 |
| `src/views/digital-human/components/DigitalHumanTranscript.vue` | NEW | 角色/生成/中断/保存同意 |
| `src/views/digital-human/composables/useDigitalHumanTranscript.ts` | NEW | 版本与contentEpoch |
| `src/views/digital-human/components/DigitalHumanEndPanel.vue` | NEW | 待核对费用/保存窗口/删除说明 |
| `src/views/digital-human/composables/useDigitalHumanTranscript.test.ts` | NEW | 删除墓碑/关闭同意/导出 |
| `src/views/digital-human/components/DigitalHumanTranscript.test.ts` | NEW | 不逐token朗读/长文字 |
| `tests/e2e/digital-human-workbench.spec.ts` | NEW | 真实UI+隔离Java/runtime |
| `tests/e2e/fixtures/digital-human.ts` | NEW | 合成账号、媒体/故障控制 |
| `scripts/acceptance/ci-e2e-105-s1.sh` | NEW | 复用ci-e2e隔离生命周期并补DH服务 |
| `tests/deployment/digital-human-s1-runner.test.ts` | NEW | 只合成栈/每引擎重置/无公共provider |
| `scripts/ci-e2e.sh` | MODIFY | 新增默认关闭测试扩展点，旧入口不受影响 |
| `deploy/digital-human/compose.test.yml` | NEW | 自有测试账号/无持久内容Redis/本地证书 |
| `scripts/acceptance/task-105-test-certificates.sh` | NEW | 仅本地测试目录生成短命证书，不提交私钥 |
| `deploy/digital-human/nginx.test.conf` | NEW | E首个同源测试代理，H验证生产模板 |
| `nginx.conf` | MODIFY | 仅AI端口82允许microphone self |
| `tests/deployment/edge-entrypoint.contract.test.ts` | MODIFY | 新增AI麦克风例外，保80/81/CSP其他原断言 |

禁止：改根依赖/lock（除本书明确列入）、改历史迁移、给旧大视图增业务逻辑、修改组织BYOK/取消退款口径、持久化grant/key/原音频、更新既有生产开关、全仓格式化、删除失败测试、杜撰测试PASS。共用契约/PRD只读；发现实质冲突先按§13给具体修订建议。

## 10. 卡顺序、依赖与估算

| 卡 | 目标 | 依赖 | 估计人日 | 初始状态 |
| --- | --- | --- | --- | --- |
|  C105E-01 | 独立路由、入口与URL恢复 | C105D-06 | 2～3 | VERIFIED（V105E-01 | 独立路由、入口与URL恢复 | C105D-01 exit0；证据 test-artifacts/task-105/E/） |
|  C105E-02 | 角色配置、形象音色与开始确认 | C105E-01 | 3～4 | VERIFIED（V105E-02 | 角色配置、形象音色与开始确认 | C105E-01 exit0；证据 test-artifacts/task-105/E/） |
|  C105E-03 | 会话状态、事件与媒体播放 | C105E-02 | 4～6 | VERIFIED（V105E-03 | 会话状态、事件与媒体播放 | C105E-01 exit0；证据 test-artifacts/task-105/E/） |
|  C105E-04 | 按键麦克风、发送与打断交互 | C105E-03 | 3～5 | VERIFIED（V105E-04 | 按键麦克风、发送与打断交互 | C105E-01 exit0；证据 test-artifacts/task-105/E/） |
|  C105E-05 | 字幕保存、历史入口与会话收尾 | C105E-04 | 2～3 | VERIFIED（V105E-05 | 字幕保存、历史入口与会话收尾 | C105E-01 exit0；证据 test-artifacts/task-105/E/） |
|  C105E-06 | S1真浏览器与视觉矩阵验收 | C105E-05 | 3～4 | VERIFIED（V105E-06 | S1真浏览器与视觉矩阵验收 | C105E-01 exit0；证据 test-artifacts/task-105/E/） |

人日是包含实现/测试/调试的计划区间，不是模型运行时长或日历承诺；第三方服务开通/服务条款核验及实机等待不计。A04(Fake)只要求隔离Fake与缺口清单；H03(conditional-beta)只约束真实开放。

## 11. 逐卡执行规格

### C105E-01 独立路由、入口与URL恢复

需求：DH-R01、DH-R02、DH-R19、DH-R20。依赖：C105D-06。必读共享：K02、K03、K11、K13、K14；本卡接口/字段不另定义替代名。

**输入与交付**：输入为前置卡的已验收契约/代码/合成fixture；输出为下表文件中的具体能力与本卡四组测试。每组测试必须落实参数与副作用断言，代码编译不代替行为验收。列出的测试文件负责自动可证部分；涉及截图、真实设备、真实provider的Then必须另执行本卡手工/条件门禁，不以单测替代。

| 完整路径 | 动作 | 定位/新增符号 | 本卡责任 |
| --- | --- | --- | --- |
| `src/ai/router.ts` | MODIFY | `routes children` | 新增digital-human lazy route，不扩四能力枚举 |
| `src/ai/AiAppLayout.vue` | MODIFY | `workspace navigation mount` | 只装配导航子组件与既有login事件 |
| `src/ai/components/AiWorkspaceNavigation.vue` | NEW | `RouterLink` | 创作中心/数字人真实导航 |
| `src/ai/components/AiWorkspaceNavigation.test.ts` | NEW | `nav contract` | 模式/可见/键盘导航 |
| `src/views/digital-human/DigitalHumanWorkbench.vue` | NEW | `assembly` | 只组合composables/子组件 |
| `src/views/digital-human/useDigitalHumanUrlState.ts` | NEW | `parse/replaceState` | profileId/sessionId/view公开ID状态 |
| `src/views/digital-human/useDigitalHumanUrlState.test.ts` | NEW | `URL tests` | 刷新/非法ID/查询净化 |
| `src/style.css` | MODIFY | `DH reusable styles only` | 复用现有token/gl类；必要数字人布局与新增token明暗成对，不重写全局主题 |

**只读源码锚点**：`src/ai/router.ts`，`children:`，快照行24起。先核实真实上下文：

```text
    component: () => import('./AiAppLayout.vue'),
    children: [
      {
        path: '',
        name: 'create',
        component: () => import('../views/ai-center/AiCreationCenter.vue'),
      },
```

**目标签名/形状**（NEW表示计划，不是已有事实）：

- `useDigitalHumanUrlState(route: RouteLocationNormalizedLoaded,router: Router): {selection: Readonly<Ref<DigitalHumanSelection>>; replaceSelection(next: DigitalHumanSelection): Promise<void>}。`
- `DigitalHumanSelection={profileId:string|null,sessionId:string|null,view:"workbench"|"history"}；URL无票据/正文/媒体签名。`

**严格执行步骤**：

1. 修改前完整读根DESIGN.md、检查GlModal/EmptyState/全局类；导航放壳的新子组件，不增AiCreationCenter豁免体积。
2. 路由深链可打开，开关关闭显示服务未开放，不自动create session；匿名触发现有request-login事件，禁止新登录token存储。
3. URL只解析严格UUID，数组/空/非法值删除；未知query不传播敏感值；replace而非每字幕变化push history。
4. 刷新sessionId先本人GET，不存在/无权同状态；未保存草稿丢失明确说明，不从storage猜恢复。
5. 只挂载当前入口，没有用户端同名第二工作台；给main标题/导航aria-current，移动链接可触达。

**边界与禁止项**：先验证本卡输入/owner/状态，再副作用；遇重复/过期/旧epoch按K01/K04返回确定结果；不得靠客户端禁按钮替代后端约束。mock仅替换外部依赖，不能mock本卡要证明的事务、鉴权、文件真实性或账务幂等。§12边界矩阵指定专项责任卡，本卡涉及的同类负例不能省略。

**Given / When / Then 验收组**：

#### TC105E-01-01 路由与深链

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P1；DH-R01、DH-R02、DH-R19、DH-R20 |
| 实现位置 | `src/ai/components/AiWorkspaceNavigation.test.ts`；测试名称包含`tc105e_01_01`；跨语言同TC可各有子断言 |
| Given | AI登录/匿名/关闭开关三状态 |
| When | 打开/digital-human |
| Then | 状态正确且provider调用0，登录沿已有入口 |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-01-01、V105E-01-02；`test-artifacts/task-105/E/C105E-01/`记录日志及原测试report引用；初始NOT_RUN |

#### TC105E-01-02 URL安全

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P0；DH-R01、DH-R02、DH-R19、DH-R20 |
| 实现位置 | `src/views/digital-human/useDigitalHumanUrlState.test.ts`；测试名称包含`tc105e_01_02`；跨语言同TC可各有子断言 |
| Given | token/prompt/坏UUID/重复query |
| When | 解析与replace |
| Then | 只保三项公开状态，敏感值不持久 |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-01-01、V105E-01-02；`test-artifacts/task-105/E/C105E-01/`记录日志及原测试report引用；初始NOT_RUN |

#### TC105E-01-03 导航语义

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P1；DH-R01、DH-R02、DH-R19、DH-R20 |
| 实现位置 | `src/ai/components/AiWorkspaceNavigation.test.ts`；测试名称包含`tc105e_01_03`；跨语言同TC可各有子断言 |
| Given | 键盘Tab与浏览器前后退 |
| When | 切两工作区 |
| Then | RouterLink正确、当前aria-current，不冒充tab |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-01-01、V105E-01-02；`test-artifacts/task-105/E/C105E-01/`记录日志及原测试report引用；初始NOT_RUN |

#### TC105E-01-04 布局基础

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P1；DH-R01、DH-R02、DH-R19、DH-R20 |
| 实现位置 | `src/ai/components/AiWorkspaceNavigation.test.ts`；测试名称包含`tc105e_01_04`；跨语言同TC可各有子断言 |
| Given | light/dark 1440与390 |
| When | 浏览器截图 |
| Then | 无营销hero、页面不横溢、focus可见 |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-01-01、V105E-01-02；`test-artifacts/task-105/E/C105E-01/`记录日志及原测试report引用；初始NOT_RUN |

**本卡交接**：汇报实现文件、四组TC实际结果、命令退出码、未运行条件、契约偏差和下一卡前置是否满足。存在P0失败不得进入依赖卡；无依赖的本书后续卡可按已写依赖继续，不自行扩大范围。

### C105E-02 角色配置、形象音色与开始确认

需求：DH-R03、DH-R04、DH-R05、DH-R06、DH-R19。依赖：C105E-01。必读共享：K01、K02、K03、K11、K13、K14；本卡接口/字段不另定义替代名。

**输入与交付**：输入为前置卡的已验收契约/代码/合成fixture；输出为下表文件中的具体能力与本卡四组测试。每组测试必须落实参数与副作用断言，代码编译不代替行为验收。列出的测试文件负责自动可证部分；涉及截图、真实设备、真实provider的Then必须另执行本卡手工/条件门禁，不以单测替代。

| 完整路径 | 动作 | 定位/新增符号 | 本卡责任 |
| --- | --- | --- | --- |
| `src/views/digital-human/components/DigitalHumanProfileForm.vue` | NEW | `ProfileForm` | 字段/兼容/版本冲突 |
| `src/views/digital-human/components/DigitalHumanStartDialog.vue` | NEW | `StartDialog` | 来源/限制/费用/保存同意 |
| `src/views/digital-human/components/DigitalHumanAvatarPicker.vue` | NEW | `AvatarPicker` | 可用预设与失效说明 |
| `src/views/digital-human/composables/useDigitalHumanProfiles.ts` | NEW | `profile flow` | 取数、保存、试听、preflight |
| `src/views/digital-human/composables/useDigitalHumanProfiles.test.ts` | NEW | `profile flows` | 失败保输入/幂等/换号 |
| `src/views/digital-human/components/DigitalHumanProfileForm.test.ts` | NEW | `form boundaries` | 输入法/长文本/错误 |
| `src/style.css` | MODIFY | `DH reusable styles only` | 复用现有token/gl类；必要数字人布局与新增token明暗成对，不重写全局主题 |
| `src/views/digital-human/DigitalHumanWorkbench.vue` | MODIFY_AFTER_C105E-01 | `assembly only` | 连接本卡composable与子组件，只增加装配不堆业务 |

**只读源码锚点**：`src/components/GlModal.vue`，`defineProps`，快照行24起。先核实真实上下文：

```text
/** Shared modal shell; business decisions stay with the caller. */
const props = withDefaults(defineProps<{
  title: string
  wide?: boolean
  scroll?: boolean
  /** Keep a pending/unsaved flow open on Escape or backdrop clicks. */
  persistent?: boolean
```

**目标签名/形状**（NEW表示计划，不是已有事实）：

- `useDigitalHumanProfiles(api:DigitalHumanApi,account:AccountSessionPort): {profiles:Ref<Profile[]>;draft:Ref<ProfileInput>;load:()=>Promise<void>;save:()=>Promise<void>;preview:(voiceId:string)=>Promise<void>;preflight:(mode:InputMode)=>Promise<Preflight|null>}。`
- `StartDialog props={preflight:Preflight|null,open:boolean,submitting:boolean,error:string|null}；emit confirm({saveTranscript:boolean})/close。`

**严格执行步骤**：

1. name/persona/greeting/tone依K02校验，字数显示Unicode码点；field error贴近输入并aria-describedby，提交期间只锁本表单，结束会话按钮不锁。
2. catalog加载失败保留已有角色；无后端与无角色区分空态，已撤形象不可直接开始；选voice依据兼容组合，不展示任意模型URL。
3. 试听固定句显式点击，换试听或换号停止前一个audio/撤object URL；超频显示Retry-After，不循环请求。
4. 开始dialog列出llm/stt/tts/render四项来源与费用，明确text来源、平台转写/配音、第三方render补贴与平台实际成本、取消规则、限时、默认不保存；点击确认才create，preflight失效回到确认不自动改价继续。
5. 版本冲突保留本地draft并提示重新载入，不偷偷覆盖；失败create若已知operationId则查询；丢首次响应无id时重发相同payload和requestId取得原结果，不生成新key。

**边界与禁止项**：先验证本卡输入/owner/状态，再副作用；遇重复/过期/旧epoch按K01/K04返回确定结果；不得靠客户端禁按钮替代后端约束。mock仅替换外部依赖，不能mock本卡要证明的事务、鉴权、文件真实性或账务幂等。§12边界矩阵指定专项责任卡，本卡涉及的同类负例不能省略。

**Given / When / Then 验收组**：

#### TC105E-02-01 配置边界与并发

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P1；DH-R03、DH-R04、DH-R05、DH-R06、DH-R19 |
| 实现位置 | `src/views/digital-human/components/DigitalHumanProfileForm.test.ts`；测试名称包含`tc105e_02_01`；跨语言同TC可各有子断言 |
| Given | emoji名字40/41、person4000/4001及旧version |
| When | 保存 |
| Then | 合法成功，非法字段提示，冲突保留输入 |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-02-01、V105E-02-02；`test-artifacts/task-105/E/C105E-02/`记录日志及原测试report引用；初始NOT_RUN |

#### TC105E-02-02 费用确认

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P0；DH-R03、DH-R04、DH-R05、DH-R06、DH-R19 |
| 实现位置 | `src/views/digital-human/composables/useDigitalHumanProfiles.test.ts`；测试名称包含`tc105e_02_02`；跨语言同TC可各有子断言 |
| Given | own text+platform audio组合 |
| When | 打开开始dialog |
| Then | 分项明确、save默认false，无确认不create |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-02-01、V105E-02-02；`test-artifacts/task-105/E/C105E-02/`记录日志及原测试report引用；初始NOT_RUN |

#### TC105E-02-03 失败与空态

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P1；DH-R03、DH-R04、DH-R05、DH-R06、DH-R19 |
| 实现位置 | `src/views/digital-human/components/DigitalHumanProfileForm.test.ts`；测试名称包含`tc105e_02_03`；跨语言同TC可各有子断言 |
| Given | catalog503/无后端/无角色 |
| When | 加载 |
| Then | 三种文案与下一步不同，不把503当空角色 |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-02-01、V105E-02-02；`test-artifacts/task-105/E/C105E-02/`记录日志及原测试report引用；初始NOT_RUN |

#### TC105E-02-04 试听重入与换号

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P0；DH-R03、DH-R04、DH-R05、DH-R06、DH-R19 |
| 实现位置 | `src/views/digital-human/composables/useDigitalHumanProfiles.test.ts`；测试名称包含`tc105e_02_04`；跨语言同TC可各有子断言 |
| Given | 试听下载Promise延迟 |
| When | 换号再回调 |
| Then | 不播放旧音频、无旧objectURL泄漏 |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-02-01、V105E-02-02；`test-artifacts/task-105/E/C105E-02/`记录日志及原测试report引用；初始NOT_RUN |

**本卡交接**：汇报实现文件、四组TC实际结果、命令退出码、未运行条件、契约偏差和下一卡前置是否满足。存在P0失败不得进入依赖卡；无依赖的本书后续卡可按已写依赖继续，不自行扩大范围。

### C105E-03 会话状态、事件与媒体播放

需求：DH-R07、DH-R09、DH-R10、DH-R11、DH-R15。依赖：C105E-02。必读共享：K04、K06、K07、K11、K13、K14；本卡接口/字段不另定义替代名。

**输入与交付**：输入为前置卡的已验收契约/代码/合成fixture；输出为下表文件中的具体能力与本卡四组测试。每组测试必须落实参数与副作用断言，代码编译不代替行为验收。列出的测试文件负责自动可证部分；涉及截图、真实设备、真实provider的Then必须另执行本卡手工/条件门禁，不以单测替代。

| 完整路径 | 动作 | 定位/新增符号 | 本卡责任 |
| --- | --- | --- | --- |
| `src/views/digital-human/composables/useDigitalHumanSession.ts` | NEW | `session lifecycle` | create/pause/resume/end+epoch |
| `src/views/digital-human/composables/useDigitalHumanEvents.ts` | NEW | `SSE parser` | 逐块事件/快照/重连 |
| `src/views/digital-human/composables/useDigitalHumanMedia.ts` | NEW | `peer lifecycle` | recvonly完整ICE/media reset |
| `src/views/digital-human/components/DigitalHumanStage.vue` | NEW | `Stage` | 状态/媒体错误/AI标识 |
| `src/views/digital-human/components/DigitalHumanUsage.vue` | NEW | `Usage` | confirmed/pending/来源 |
| `src/views/digital-human/composables/useDigitalHumanSession.test.ts` | NEW | `session tests` | 定时器/失活/旧票 |
| `src/views/digital-human/composables/useDigitalHumanEvents.test.ts` | NEW | `SSE tests` | 跨chunk/seq/内容墓碑 |
| `src/views/digital-human/composables/useDigitalHumanMedia.test.ts` | NEW | `media tests` | track/peer/自动播放 |
| `src/style.css` | MODIFY | `DH reusable styles only` | 复用现有token/gl类；必要数字人布局与新增token明暗成对，不重写全局主题 |
| `src/views/digital-human/DigitalHumanWorkbench.vue` | MODIFY_AFTER_C105E-01 | `assembly only` | 连接本卡composable与子组件，只增加装配不堆业务 |

**只读源码锚点**：`src/stores/account-session.ts`，`export interface AccountSessionPort`，快照行20起。先核实真实上下文：

```text
/** 消费方（设置/积分/身份/工作台等私有域）与账号会话的唯一对接面。 */
export interface AccountSessionPort {
  capture(): AccountTicket
  isCurrent(ticket: AccountTicket): boolean
}

/**
```

**目标签名/形状**（NEW表示计划，不是已有事实）：

- `useDigitalHumanSession(api,account): {session:ShallowRef<Session|null>;start:(p:Preflight,save:boolean)=>Promise<void>;pause:()=>Promise<void>;resume:(takeover:boolean)=>Promise<void>;end:()=>Promise<void>;dispose:()=>void}，参数类型引用B导出，不接受any。`
- `useDigitalHumanMedia(api,account): {stream:ShallowRef<MediaStream|null>;connect:(session:Session)=>Promise<void>;reset:(epoch:number)=>Promise<void>;stop:()=>void}。`
- `parseSse(chunks:AsyncIterable<Uint8Array>): AsyncGenerator<DhEvent>；TextDecoder streaming，支持多行data/CRLF/注释，单事件≤64KiB。`

**严格执行步骤**：

1. 每次Promise回写前ticket+generation+session/lease/mediaEpoch验证；AbortSignal仅取消读取，不能据此显示退款。
2. create/preflight/offer/media-ready顺序明确；到ready前禁输入；autoplay失败显示“点击播放”而非ready无声，stage保留错误与重新连接入口。
3. SSE维护snapshot状态水位与字幕replay水位，严格按K06重放后才接live并按eventId去重；缺口显示“部分实时字幕未恢复”，只查状态不重发turn；新账号旧事件丢弃。
4. heartbeat只活动页面有效session每10秒；隐藏pause，deactivated/end，beforeunload尽力发送但最终依赖server lease；visible不能激活仍deactivated页。
5. interrupt本地立刻静音/停止srcObject并API20；收到mediaEpoch递增后重建peer，旧ICE/track回调关闭；结束始终可点击且幂等。

**边界与禁止项**：先验证本卡输入/owner/状态，再副作用；遇重复/过期/旧epoch按K01/K04返回确定结果；不得靠客户端禁按钮替代后端约束。mock仅替换外部依赖，不能mock本卡要证明的事务、鉴权、文件真实性或账务幂等。§12边界矩阵指定专项责任卡，本卡涉及的同类负例不能省略。

**Given / When / Then 验收组**：

#### TC105E-03-01 SSE真实分块

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P1；DH-R07、DH-R09、DH-R10、DH-R11、DH-R15 |
| 实现位置 | `src/views/digital-human/composables/useDigitalHumanEvents.test.ts`；测试名称包含`tc105e_03_01`；跨语言同TC可各有子断言 |
| Given | UTF8中文/CRLF/多行data跨边界 |
| When | 解析及重连 |
| Then | 不乱码不重复，seq缺口触发snapshot |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-03-01、V105E-03-02；`test-artifacts/task-105/E/C105E-03/`记录日志及原测试report引用；初始NOT_RUN |

#### TC105E-03-02 A→B→A迟到

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P0；DH-R07、DH-R09、DH-R10、DH-R11、DH-R15 |
| 实现位置 | `src/views/digital-human/composables/useDigitalHumanSession.test.ts`；测试名称包含`tc105e_03_02`；跨语言同TC可各有子断言 |
| Given | 账号切两次后旧offer和字幕返回 |
| When | 提交状态 |
| Then | 全部丢弃且关闭旧track |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-03-01、V105E-03-02；`test-artifacts/task-105/E/C105E-03/`记录日志及原测试report引用；初始NOT_RUN |

#### TC105E-03-03 隐藏与KeepAlive

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P0；DH-R07、DH-R09、DH-R10、DH-R11、DH-R15 |
| 实现位置 | `src/views/digital-human/composables/useDigitalHumanSession.test.ts`；测试名称包含`tc105e_03_03`；跨语言同TC可各有子断言 |
| Given | deactivated后visible事件 |
| When | 触发心跳/恢复 |
| Then | 不恢复采集/peer、不续租旧会话 |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-03-01、V105E-03-02；`test-artifacts/task-105/E/C105E-03/`记录日志及原测试report引用；初始NOT_RUN |

#### TC105E-03-04 自动播放/ICE失败

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P1；DH-R07、DH-R09、DH-R10、DH-R11、DH-R15 |
| 实现位置 | `src/views/digital-human/composables/useDigitalHumanMedia.test.ts`；测试名称包含`tc105e_03_04`；跨语言同TC可各有子断言 |
| Given | play拒绝或ICE10秒未完成 |
| When | 连接 |
| Then | 有手动动作与超时错误，无假ready；重试不新建session |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-03-01、V105E-03-02；`test-artifacts/task-105/E/C105E-03/`记录日志及原测试report引用；初始NOT_RUN |

**本卡交接**：汇报实现文件、四组TC实际结果、命令退出码、未运行条件、契约偏差和下一卡前置是否满足。存在P0失败不得进入依赖卡；无依赖的本书后续卡可按已写依赖继续，不自行扩大范围。

### C105E-04 按键麦克风、发送与打断交互

需求：DH-R07、DH-R08、DH-R10、DH-R19。依赖：C105E-03。必读共享：K01、K07、K11、K13、K14；本卡接口/字段不另定义替代名。

**输入与交付**：输入为前置卡的已验收契约/代码/合成fixture；输出为下表文件中的具体能力与本卡四组测试。每组测试必须落实参数与副作用断言，代码编译不代替行为验收。列出的测试文件负责自动可证部分；涉及截图、真实设备、真实provider的Then必须另执行本卡手工/条件门禁，不以单测替代。

| 完整路径 | 动作 | 定位/新增符号 | 本卡责任 |
| --- | --- | --- | --- |
| `src/views/digital-human/composables/useDigitalHumanMicrophone.ts` | NEW | `microphone state machine` | 显式权限、worklet/WS/stop |
| `src/views/digital-human/audio/pcm-worklet.ts` | NEW | `Pcm16kProcessor` | 连续重采样与int16编码 |
| `src/views/digital-human/audio/pcm-resampler.ts` | NEW | `PcmResampler` | 纯重采样可测算法 |
| `src/views/digital-human/components/DigitalHumanComposer.vue` | NEW | `text/mic/interrupt controls` | Enter/IME/状态与错误 |
| `src/views/digital-human/composables/useDigitalHumanMicrophone.test.ts` | NEW | `media permission races` | 迟到权限/close/背压 |
| `src/views/digital-human/audio/pcm-resampler.test.ts` | NEW | `resampling vectors` | 44100/48000→16000保持时长与频率 |
| `src/style.css` | MODIFY | `DH reusable styles only` | 复用现有token/gl类；必要数字人布局与新增token明暗成对，不重写全局主题 |
| `src/views/digital-human/DigitalHumanWorkbench.vue` | MODIFY_AFTER_C105E-01 | `assembly only` | 连接本卡composable与子组件，只增加装配不堆业务 |

**只读源码锚点**：`src/stores/account-session.ts`，`export interface AccountTicket`，快照行13起。先核实真实上下文：

```text
 */
export interface AccountTicket {
  readonly accountId: string | null
  readonly epoch: number
  readonly signal: AbortSignal
}

```

**目标签名/形状**（NEW表示计划，不是已有事实）：

- `PcmResampler.push(input:Float32Array,inputRate:number):Int16Array；持跨chunk相位与低通滤波状态，单声道16k、clip[-1,1]；reset():void。`
- `useDigitalHumanMicrophone(api,account): {state:Ref<MicState>;start:()=>Promise<void>;submit:()=>Promise<void>;abort:()=>Promise<void>;dispose:()=>void}；MicState=idle/requesting/recording/transcribing/error。`

**严格执行步骤**：

1. getUserMedia仅显式点击start，audio echoCancellation/noiseSuppression可请求但不要以浏览器实际采样率等于16k为前提；一期不申请camera。
2. AudioWorklet由new URL(...,import.meta.url)经Vite本地打包；无外部CDN，不用已废弃ScriptProcessor偷渡。Worklet编码20ms块，header seq+samples按K07。
3. 重采样用带低通的多相/窗sinc确定实现，filter taps63、cutoff=min(8000,inputRate/2)×0.9；保留边界相位，单测高频alias受抑，不能仅线性抽样造成质量退化。
4. 结束发送end后立即stop所有tracks/disconnect worklet/close AudioContext，WS等待识别状态不继续采集；60秒自动submit、拒权可继续text。
5. 发言期间普通send禁用；“打断并说话”先本地停+确认新media ready后才start mic，不能旧轮与新录音抢同一turn。
6. 权限Promise迟到时若ticket失效，立即stop刚拿到的tracks；背压2秒abort并提示重新录制，不静默丢帧。

**边界与禁止项**：先验证本卡输入/owner/状态，再副作用；遇重复/过期/旧epoch按K01/K04返回确定结果；不得靠客户端禁按钮替代后端约束。mock仅替换外部依赖，不能mock本卡要证明的事务、鉴权、文件真实性或账务幂等。§12边界矩阵指定专项责任卡，本卡涉及的同类负例不能省略。

**Given / When / Then 验收组**：

#### TC105E-04-01 采样精度

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P1；DH-R07、DH-R08、DH-R10、DH-R19 |
| 实现位置 | `src/views/digital-human/audio/pcm-resampler.test.ts`；测试名称包含`tc105e_04_01`；跨语言同TC可各有子断言 |
| Given | 1kHz/10kHz测试波44.1与48k |
| When | 不同长度chunk重采样 |
| Then | 样本数误差≤1，频率正确、alias受抑，连续边界无爆音 |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-04-01、V105E-04-02；`test-artifacts/task-105/E/C105E-04/`记录日志及原测试report引用；初始NOT_RUN |

#### TC105E-04-02 权限晚到

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P0；DH-R07、DH-R08、DH-R10、DH-R19 |
| 实现位置 | `src/views/digital-human/composables/useDigitalHumanMicrophone.test.ts`；测试名称包含`tc105e_04_02`；跨语言同TC可各有子断言 |
| Given | start后换号/卸载再resolve media |
| When | resolve |
| Then | 所有tracks马上stop，state不回recording |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-04-01、V105E-04-02；`test-artifacts/task-105/E/C105E-04/`记录日志及原测试report引用；初始NOT_RUN |

#### TC105E-04-03 长音频/背压

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P1；DH-R07、DH-R08、DH-R10、DH-R19 |
| 实现位置 | `src/views/digital-human/composables/useDigitalHumanMicrophone.test.ts`；测试名称包含`tc105e_04_03`；跨语言同TC可各有子断言 |
| Given | 60秒、WS bufferedAmount超阈值 |
| When | 持续收音 |
| Then | 到限submit或拥堵abort，CPU/内存/设备释放 |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-04-01、V105E-04-02；`test-artifacts/task-105/E/C105E-04/`记录日志及原测试report引用；初始NOT_RUN |

#### TC105E-04-04 输入法/打断

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P1；DH-R07、DH-R08、DH-R10、DH-R19 |
| 实现位置 | `src/views/digital-human/composables/useDigitalHumanMicrophone.test.ts`；测试名称包含`tc105e_04_04`；跨语言同TC可各有子断言 |
| Given | 中文IME composing与responding |
| When | 按Enter/显式打断 |
| Then | IME不提交，普通发送不重入，新mic只在新peer ready |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-04-01、V105E-04-02；`test-artifacts/task-105/E/C105E-04/`记录日志及原测试report引用；初始NOT_RUN |

**本卡交接**：汇报实现文件、四组TC实际结果、命令退出码、未运行条件、契约偏差和下一卡前置是否满足。存在P0失败不得进入依赖卡；无依赖的本书后续卡可按已写依赖继续，不自行扩大范围。

### C105E-05 字幕保存、历史入口与会话收尾

需求：DH-R11、DH-R12、DH-R15、DH-R19。依赖：C105E-04。必读共享：K03、K06、K09、K11、K13、K14；本卡接口/字段不另定义替代名。

**输入与交付**：输入为前置卡的已验收契约/代码/合成fixture；输出为下表文件中的具体能力与本卡四组测试。每组测试必须落实参数与副作用断言，代码编译不代替行为验收。列出的测试文件负责自动可证部分；涉及截图、真实设备、真实provider的Then必须另执行本卡手工/条件门禁，不以单测替代。

| 完整路径 | 动作 | 定位/新增符号 | 本卡责任 |
| --- | --- | --- | --- |
| `src/views/digital-human/components/DigitalHumanTranscript.vue` | NEW | `transcript display` | 角色/生成/中断/保存同意 |
| `src/views/digital-human/composables/useDigitalHumanTranscript.ts` | NEW | `preference/save/export/delete` | 版本与contentEpoch |
| `src/views/digital-human/components/DigitalHumanEndPanel.vue` | NEW | `end result` | 待核对费用/保存窗口/删除说明 |
| `src/views/digital-human/composables/useDigitalHumanTranscript.test.ts` | NEW | `transcript races` | 删除墓碑/关闭同意/导出 |
| `src/views/digital-human/components/DigitalHumanTranscript.test.ts` | NEW | `accessibility` | 不逐token朗读/长文字 |
| `src/style.css` | MODIFY | `DH reusable styles only` | 复用现有token/gl类；必要数字人布局与新增token明暗成对，不重写全局主题 |
| `src/views/digital-human/DigitalHumanWorkbench.vue` | MODIFY_AFTER_C105E-01 | `assembly only` | 连接本卡composable与子组件，只增加装配不堆业务 |

**只读源码锚点**：`src/composables/grassland-http.ts`，`export async function fetchApi`，快照行153起。先核实真实上下文：

```text
 */
export async function fetchApi(url: string, init: RequestInit = {}): Promise<Response> {
  return fetch(url, {
    credentials: 'include',
    ...init,
    headers: shouldDefaultJsonContentType(init.body)
      ? { 'Content-Type': 'application/json', ...(init.headers || {}) }
```

**目标签名/形状**（NEW表示计划，不是已有事实）：

- `useDigitalHumanTranscript(api,account,session): {entries:Ref<TranscriptEntry[]>;setSave:(value:boolean)=>Promise<void>;saveNow:()=>Promise<void>;exportTxt:()=>Promise<void>;deleteSaved:()=>Promise<void>;clear:()=>void}。`

**严格执行步骤**：

1. 生成delta与final分开展示，SRT相关操作本阶段不出现未实现按钮；中断文本标记，不显示成完整播报。
2. save默认false，toggle true明确说明可保存仍在buffer的final；关闭后不删旧记录，删除要GlModal确认并服务端contentEpoch更新。
3. 结束panel显示保存窗口截止、已确认费用/待核对调用，0仅真正0；超10分钟按钮失效不能从内存悄悄重新上传已过期内容。
4. 导出仅已保存文本；ObjectURL下载后撤销，失败保留内容与手动重试；整个正文不进任何浏览器持久存储。
5. 历史列表API09由G实现，在E阶段入口可切到已保存当前会话结果，最终跨会话历史待G，不能标R12全完成直到G；文档状态按依赖报告。

**边界与禁止项**：先验证本卡输入/owner/状态，再副作用；遇重复/过期/旧epoch按K01/K04返回确定结果；不得靠客户端禁按钮替代后端约束。mock仅替换外部依赖，不能mock本卡要证明的事务、鉴权、文件真实性或账务幂等。§12边界矩阵指定专项责任卡，本卡涉及的同类负例不能省略。

**Given / When / Then 验收组**：

#### TC105E-05-01 同意与生成区分

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P1；DH-R11、DH-R12、DH-R15、DH-R19 |
| 实现位置 | `src/views/digital-human/composables/useDigitalHumanTranscript.test.ts`；测试名称包含`tc105e_05_01`；跨语言同TC可各有子断言 |
| Given | delta/final/interrupted事件 |
| When | toggle保存 |
| Then | 只final按同意落，UI状态真实 |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-05-01、V105E-05-02；`test-artifacts/task-105/E/C105E-05/`记录日志及原测试report引用；初始NOT_RUN |

#### TC105E-05-02 删除后旧事件

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P0；DH-R11、DH-R12、DH-R15、DH-R19 |
| 实现位置 | `src/views/digital-human/composables/useDigitalHumanTranscript.test.ts`；测试名称包含`tc105e_05_02`；跨语言同TC可各有子断言 |
| Given | 删除成功后旧contentEpoch到达 |
| When | 回写字幕 |
| Then | 不复活；已保存素材删除说明清晰 |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-05-01、V105E-05-02；`test-artifacts/task-105/E/C105E-05/`记录日志及原测试report引用；初始NOT_RUN |

#### TC105E-05-03 窗口与待核对

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P1；DH-R11、DH-R12、DH-R15、DH-R19 |
| 实现位置 | `src/views/digital-human/composables/useDigitalHumanTranscript.test.ts`；测试名称包含`tc105e_05_03`；跨语言同TC可各有子断言 |
| Given | ended+10分钟、unknown fee |
| When | 显示/导出 |
| Then | 过期不能保存，金额显示待核对而非0 |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-05-01、V105E-05-02；`test-artifacts/task-105/E/C105E-05/`记录日志及原测试report引用；初始NOT_RUN |

#### TC105E-05-04 无障碍长文本

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P1；DH-R11、DH-R12、DH-R15、DH-R19 |
| 实现位置 | `src/views/digital-human/components/DigitalHumanTranscript.test.ts`；测试名称包含`tc105e_05_04`；跨语言同TC可各有子断言 |
| Given | 2000码点、多轮内容 |
| When | 键盘与读屏状态检查 |
| Then | 可滚动/复制，aria-live只状态不逐token刷屏 |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-05-01、V105E-05-02；`test-artifacts/task-105/E/C105E-05/`记录日志及原测试report引用；初始NOT_RUN |

**本卡交接**：汇报实现文件、四组TC实际结果、命令退出码、未运行条件、契约偏差和下一卡前置是否满足。存在P0失败不得进入依赖卡；无依赖的本书后续卡可按已写依赖继续，不自行扩大范围。

### C105E-06 S1真浏览器与视觉矩阵验收

需求：DH-R01、DH-R02、DH-R07、DH-R08、DH-R09、DH-R10、DH-R11、DH-R12、DH-R19。依赖：C105E-05。必读共享：K07、K11、K12、K13、K14；本卡接口/字段不另定义替代名。

**输入与交付**：输入为前置卡的已验收契约/代码/合成fixture；输出为下表文件中的具体能力与本卡四组测试。每组测试必须落实参数与副作用断言，代码编译不代替行为验收。列出的测试文件负责自动可证部分；涉及截图、真实设备、真实provider的Then必须另执行本卡手工/条件门禁，不以单测替代。

| 完整路径 | 动作 | 定位/新增符号 | 本卡责任 |
| --- | --- | --- | --- |
| `tests/e2e/digital-human-workbench.spec.ts` | NEW | `S1 browser cases` | 真实UI+隔离Java/runtime |
| `tests/e2e/fixtures/digital-human.ts` | NEW | `seed/synthetic audio` | 合成账号、媒体/故障控制 |
| `scripts/acceptance/ci-e2e-105-s1.sh` | NEW | `S1 runner` | 复用ci-e2e隔离生命周期并补DH服务 |
| `tests/deployment/digital-human-s1-runner.test.ts` | NEW | `runner contract` | 只合成栈/每引擎重置/无公共provider |
| `scripts/ci-e2e.sh` | MODIFY | `opt-in DH compose services` | 新增默认关闭测试扩展点，旧入口不受影响 |
| `deploy/digital-human/compose.test.yml` | NEW | `Fake stack` | 自有测试账号/无持久内容Redis/本地证书 |
| `scripts/acceptance/task-105-test-certificates.sh` | NEW | `ephemeral CA/SAN certificates` | 仅本地测试目录生成短命证书，不提交私钥 |
| `deploy/digital-human/nginx.test.conf` | NEW | `local ws/sse proxy` | E首个同源测试代理，H验证生产模板 |
| `nginx.conf` | MODIFY | `AI server Permissions-Policy` | 仅AI端口82允许microphone self |
| `tests/deployment/edge-entrypoint.contract.test.ts` | MODIFY | `per-entry policy` | 新增AI麦克风例外，保80/81/CSP其他原断言 |

**只读源码锚点**：`scripts/acceptance/ci-e2e-103-only.sh`，`E2E_SPECS`，快照行24起。先核实真实上下文：

```text

export E2E_SPECS="tests/e2e/task103-consistency.spec.ts tests/e2e/task103-ui.spec.ts tests/e2e/task103-dispute-lifecycle.spec.ts tests/e2e/task98-full-chain.spec.ts"
export MARKETPLACE_COMMERCE_SPLIT_COOLDOWN_SECONDS_OVERRIDE="${MARKETPLACE_COMMERCE_SPLIT_COOLDOWN_SECONDS_OVERRIDE:-15}"
export E2E_SHOT_DIR="${E2E_SHOT_DIR:-test-artifacts/task-103/screenshots/e2e}"
# 三个 spec 文件共享同一隔离栈与种子账号（per-session 活动身份/内部断言），必须串行。
export E2E_WORKERS="${E2E_WORKERS:-1}"
# 复用既有分阶段启动，避免多个冷 JVM 争用同一桌面 runner；保留健康检查与超时。
```

**目标签名/形状**（NEW表示计划，不是已有事实）：

- `ci-e2e-105-s1.sh：从根调用，E2E_WORKERS=1，默认chromium firefox webkit；只显式DH_E2E=1时加入Fake wrapper等服务，真实provider强制false。`

**严格执行步骤**：

1. 先用真实Java API/Fake Python通路，不允许Playwright route.fulfill整个会话冒充后端集成；媒体设备测试可注入合成音轨但要标synthetic。
2. 矩阵1440×900/390×844 ×light/dark，另320px和200%缩放；页面至少idle/loading/empty/error/submitting/recording/ended七态。
3. 实际打开截图逐张检查对比度、token、间距、focus、长中文、移动软键盘；截图生成本身不等于视觉自查。
4. 两个真实page独立sessionStorage验证接管与A→B→A，不只直接调composable；kill tab测试server release目标。
5. 三引擎文本/事件/布局必须跑；引擎无法自动获取mic时该设备路径标PARTIAL并留给H实机，不skip后称麦克风全通过。
6. 先修AI server的Permissions-Policy microphone=(self)，仅该入口，80/81仍禁；真实Nginx部署页面验证权限，不能只在Vite里通过。E本地专用nginx.test.conf加载同一受验证策略。

**边界与禁止项**：先验证本卡输入/owner/状态，再副作用；遇重复/过期/旧epoch按K01/K04返回确定结果；不得靠客户端禁按钮替代后端约束。mock仅替换外部依赖，不能mock本卡要证明的事务、鉴权、文件真实性或账务幂等。§12边界矩阵指定专项责任卡，本卡涉及的同类负例不能省略。

**Given / When / Then 验收组**：

#### TC105E-06-01 三引擎完整流

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P1；DH-R01、DH-R02、DH-R07、DH-R08、DH-R09、DH-R10、DH-R11、DH-R12、DH-R19 |
| 实现位置 | `tests/e2e/digital-human-workbench.spec.ts`；测试名称包含`tc105e_06_01`；跨语言同TC可各有子断言 |
| Given | 隔离DB/Java/Python/Fake model |
| When | 创建→文字→打断→保存→结束 |
| Then | API真实写读、结果归本人、worker最终0 |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-06-01、V105E-06-02；`test-artifacts/task-105/E/C105E-06/`记录日志及原测试report引用；初始NOT_RUN |

#### TC105E-06-02 双页与换号

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P0；DH-R01、DH-R02、DH-R07、DH-R08、DH-R09、DH-R10、DH-R11、DH-R12、DH-R19 |
| 实现位置 | `tests/e2e/digital-human-workbench.spec.ts`；测试名称包含`tc105e_06_02`；跨语言同TC可各有子断言 |
| Given | 两个真实浏览器page |
| When | 接管/登出/回A |
| Then | 旧页无音画续命，后台租约收敛 |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-06-01、V105E-06-02；`test-artifacts/task-105/E/C105E-06/`记录日志及原测试report引用；初始NOT_RUN |

#### TC105E-06-03 视觉与键盘

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P1；DH-R01、DH-R02、DH-R07、DH-R08、DH-R09、DH-R10、DH-R11、DH-R12、DH-R19 |
| 实现位置 | `tests/e2e/digital-human-workbench.spec.ts`；测试名称包含`tc105e_06_03`；跨语言同TC可各有子断言 |
| Given | 七态×四主视口主题组合 |
| When | 截图与实际查看 |
| Then | 无缺失focus/溢出/不可读文案，必要错误有恢复动作 |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-06-01、V105E-06-02；`test-artifacts/task-105/E/C105E-06/`记录日志及原测试report引用；初始NOT_RUN |

#### TC105E-06-04 测试边界

| 项目 | 执行合同 |
| --- | --- |
| 优先级/追踪 | P1；DH-R01、DH-R02、DH-R07、DH-R08、DH-R09、DH-R10、DH-R11、DH-R12、DH-R19 |
| 实现位置 | `tests/e2e/digital-human-workbench.spec.ts`；测试名称包含`tc105e_06_04`；跨语言同TC可各有子断言 |
| Given | 某引擎mic不可用 |
| When | 生成验收结果 |
| Then | 文本UI可PASS，mic明确PARTIAL不虚报 |
| 数据与依赖 | 合成账号A/B、固定UTC时钟和已批准fixture；账号id按现有测试seed真实格式，不用生产账号。若涉及网络，只mock最外层provider；DB锁/owner/经济键使用真实实现。 |
| 边界展开 | 正文中出现多组值必须逐项参数化；并发用2个独立事务/浏览器页与屏障控制先后，时间推进Clock，不能任意sleep换绿灯。 |
| 副作用/最终状态 | 除Then外检查provider请求次数、活动槽/媒体句柄、DB行或文件引用；失败不能新增隐藏扣费/资源。状态按本卡目标进入可观察终态或明确pending。 |
| 清理 | 关闭测试tracks/peer/server、删除本用例合成数据与临时目录；不得清空共享开发库或其他任务产物。 |
| 运行/证据 | V105E-06-01、V105E-06-02；`test-artifacts/task-105/E/C105E-06/`记录日志及原测试report引用；初始NOT_RUN |

**本卡交接**：汇报实现文件、四组TC实际结果、命令退出码、未运行条件、契约偏差和下一卡前置是否满足。存在P0失败不得进入依赖卡；无依赖的本书后续卡可按已写依赖继续，不自行扩大范围。

## 12. 验收命令、边界矩阵与阶段出口

所有命令均为**实施后执行合同**，本次写任务书没有运行业务测试。工作目录“仓库根”指`/Users/LXH/claude/y-1`。每条记录开始结束、退出码、实际测试数量、报告路径；0 tests/全部SKIPPED/缓存未执行不能当本次PASS。

### V105E-01-01 Vitest（C105E-01）

目录：`仓库根`；shell：bash。前提/影响：当前npm lock与Node工具链；新增测试必须被vitest include覆盖。预期：退出0且相关TC实际通过；条件真实项无条件时NOT_RUN。证据：`test-artifacts/task-105/E/C105E-01/V105E-01-01.log`及工具原生报告。

```bash
npm run test -- src/ai/components/AiWorkspaceNavigation.test.ts src/views/digital-human/useDigitalHumanUrlState.test.ts
```

### V105E-01-02 类型（C105E-01）

目录：`仓库根`；shell：bash。前提/影响：不运行远程服务。预期：退出0且相关TC实际通过；条件真实项无条件时NOT_RUN。证据：`test-artifacts/task-105/E/C105E-01/V105E-01-02.log`及工具原生报告。

```bash
npm run typecheck
```

### V105E-02-01 Vitest（C105E-02）

目录：`仓库根`；shell：bash。前提/影响：当前npm lock与Node工具链；新增测试必须被vitest include覆盖。预期：退出0且相关TC实际通过；条件真实项无条件时NOT_RUN。证据：`test-artifacts/task-105/E/C105E-02/V105E-02-01.log`及工具原生报告。

```bash
npm run test -- src/views/digital-human/composables/useDigitalHumanProfiles.test.ts src/views/digital-human/components/DigitalHumanProfileForm.test.ts
```

### V105E-02-02 类型（C105E-02）

目录：`仓库根`；shell：bash。前提/影响：不运行远程服务。预期：退出0且相关TC实际通过；条件真实项无条件时NOT_RUN。证据：`test-artifacts/task-105/E/C105E-02/V105E-02-02.log`及工具原生报告。

```bash
npm run typecheck
```

### V105E-03-01 Vitest（C105E-03）

目录：`仓库根`；shell：bash。前提/影响：当前npm lock与Node工具链；新增测试必须被vitest include覆盖。预期：退出0且相关TC实际通过；条件真实项无条件时NOT_RUN。证据：`test-artifacts/task-105/E/C105E-03/V105E-03-01.log`及工具原生报告。

```bash
npm run test -- src/views/digital-human/composables/useDigitalHumanSession.test.ts src/views/digital-human/composables/useDigitalHumanEvents.test.ts src/views/digital-human/composables/useDigitalHumanMedia.test.ts
```

### V105E-03-02 类型（C105E-03）

目录：`仓库根`；shell：bash。前提/影响：不运行远程服务。预期：退出0且相关TC实际通过；条件真实项无条件时NOT_RUN。证据：`test-artifacts/task-105/E/C105E-03/V105E-03-02.log`及工具原生报告。

```bash
npm run typecheck
```

### V105E-04-01 Vitest（C105E-04）

目录：`仓库根`；shell：bash。前提/影响：当前npm lock与Node工具链；新增测试必须被vitest include覆盖。预期：退出0且相关TC实际通过；条件真实项无条件时NOT_RUN。证据：`test-artifacts/task-105/E/C105E-04/V105E-04-01.log`及工具原生报告。

```bash
npm run test -- src/views/digital-human/composables/useDigitalHumanMicrophone.test.ts src/views/digital-human/audio/pcm-resampler.test.ts
```

### V105E-04-02 类型（C105E-04）

目录：`仓库根`；shell：bash。前提/影响：不运行远程服务。预期：退出0且相关TC实际通过；条件真实项无条件时NOT_RUN。证据：`test-artifacts/task-105/E/C105E-04/V105E-04-02.log`及工具原生报告。

```bash
npm run typecheck
```

### V105E-05-01 Vitest（C105E-05）

目录：`仓库根`；shell：bash。前提/影响：当前npm lock与Node工具链；新增测试必须被vitest include覆盖。预期：退出0且相关TC实际通过；条件真实项无条件时NOT_RUN。证据：`test-artifacts/task-105/E/C105E-05/V105E-05-01.log`及工具原生报告。

```bash
npm run test -- src/views/digital-human/composables/useDigitalHumanTranscript.test.ts src/views/digital-human/components/DigitalHumanTranscript.test.ts
```

### V105E-05-02 类型（C105E-05）

目录：`仓库根`；shell：bash。前提/影响：不运行远程服务。预期：退出0且相关TC实际通过；条件真实项无条件时NOT_RUN。证据：`test-artifacts/task-105/E/C105E-05/V105E-05-02.log`及工具原生报告。

```bash
npm run typecheck
```

### V105E-06-01 Vitest（C105E-06）

目录：`仓库根`；shell：bash。前提/影响：当前npm lock与Node工具链；新增测试必须被vitest include覆盖。预期：退出0且相关TC实际通过；条件真实项无条件时NOT_RUN。证据：`test-artifacts/task-105/E/C105E-06/V105E-06-01.log`及工具原生报告。

```bash
npm run test -- tests/deployment/digital-human-s1-runner.test.ts tests/deployment/edge-entrypoint.contract.test.ts
```

### V105E-06-02 S1三浏览器（C105E-06）

目录：`仓库根`；shell：bash。前提/影响：本卡新增脚本，创建/清理隔离Fake compose。预期：退出0且相关TC实际通过；条件真实项无条件时NOT_RUN。证据：`test-artifacts/task-105/E/C105E-06/V105E-06-02.log`及工具原生报告。

```bash
bash scripts/acceptance/ci-e2e-105-s1.sh
```

### 阶段公共门禁

```bash
npm run typecheck
npm run lint
npm run build
npm run quality:lifecycle
npm run docs:links
npm run docs:status
npm run security:secrets
git diff --check
```

`quality:lifecycle`从B建表开始必需；A阶段仅既有基线回归，不要求不存在的DH表。UI阶段追加适用DESIGN lint和明暗截图自查；设计工具失败/真实设备缺失均记录具体范围。Java修改阶段先执行本卡定向再受影响service的`:check`；H02汇总跨域门禁，不能将本书全完成代替下一阶段。
### E01～E22边界追踪

这是整条个人版链路的精确责任矩阵；专项卡未到达时标DEPENDENCY_PENDING，不宣称当前阶段已通过它。当前卡对应情形仍在其四组TC中参数化实现；不相关场景无需在纯锁文件/纯部署卡造UI。

| 编号 | 情形 | 固定输入与预期 | 专项责任/用例 |
| --- | --- | --- | --- |
| E01 | 空输入 | text="  "、persona=""、requestId缺失；422且零派发 | C105B-03 / TC105B-03-02 |
| E02 | 超长输入 | text2001/persona4001码点、SDP65537bytes；422/413 | C105B-03 / TC105B-03-02 |
| E03 | 重复提交 | 同request同体/异体各并发2次；原资源或409 | C105B-03 / TC105B-03-03 |
| E04 | 网络失败 | HTTP接受后断连；只查原键，不换经济键 | C105D-01 / TC105D-01-02 |
| E05 | 服务端错误 | provider500/半流、runtime不可达；受控code与pending | C105D-03 / TC105D-03-02 |
| E06 | 未登录 | 无cookie且无内部身份；401、零业务写 | C105B-02 / TC105B-02-02 |
| E07 | 无权限 | 非platform_admin调用管理端；403 | C105G-03 / TC105G-03-01 |
| E08 | 数据为空 | 无角色/历史/voice/catalog；空态、不能点开始 | C105E-02 / TC105E-02-03 |
| E09 | 数据过期 | grant29999/30000ms、preflight60秒、内容结束10分钟 | C105D-02 / TC105D-02-03 |
| E10 | 页面刷新 | 现有会话刷新、建立新controller后明确接管 | C105C-02 / TC105C-02-01 |
| E11 | 用户快速切换 | A→B→A，同账号epoch已变仍拒旧回包 | C105E-03 / TC105E-03-02 |
| E12 | 组件卸载在途 | 权限对话晚返回/流未完成；stop tracks并清timer | C105E-04 / TC105E-04-02 |
| E13 | 缺值与非法枚举 | null/缺字段/空白/tone=unknown/多余orgId | C105A-02 / TC105A-02-03 |
| E14 | 数值长度边界 | 0/1/max/max+1、2^53、负金额、小数sample | C105A-02 / TC105A-02-03 |
| E15 | 并发乱序 | 两个控制器接管、旧epoch/旧seq迟到 | C105C-02 / TC105C-02-01 |
| E16 | 已提交响应丢失 | asset save完成丢响应；原id重试一次资产 | C105F-03 / TC105F-03-02 |
| E17 | 跨主体 | B访问A会话/avatar/media、组织上下文不能越权 | C105G-01 / TC105G-01-02 |
| E18 | 中途撤权删除 | 六窗口撤权/删除墓碑；晚结果不得复活 | C105G-05 / TC105G-05-03 |
| E19 | 旧配置/旧缓存 | expectedVersion/catalogVersion失效、旧core升级 | C105H-02 / TC105H-02-02 |
| E20 | 部分成功补偿失败 | 原run结算503、录制partial、事件重复 | C105D-06 / TC105D-06-01 |
| E21 | 时区/金额 | UTC日切与[from,to)、cents整数、冻结价表 | C105G-01 / TC105G-01-01 |
| E22 | 大文件列表长文案 | 10MiB+1头像、200MiB录制、101条limit、长中文布局 | C105F-01 / TC105F-01-02 |

阶段出口：本书本地必需TC/V与范围门禁通过、前置合同无漂移、所有新增资源有handler/test、下阶段能使用已验证真实接口；条件真实缺口列明。不得将FAKE_PASS写成REAL_QUALIFIED。

## 13. 何时阻塞，何时继续

明确阻塞当前依赖路径：公开字段/权限/资金口径矛盾；现有改动与本卡无法共存；必需前卡失败；已批准模型仍无法满足核心协议且需要换方案。报告必须含卡号、准确文件/符号、最小复现、预期/实际、已排查项与最小所需决定。
可继续：行号/空白变化、正常实现细节、本地依赖安装、已有服务复用、Fake测试；第三方服务配置/服务条款证据/测试额度缺少时，只停止真实实验和开放，不阻塞明确无依赖的本地开发。禁止无限重试、假数据冒充真实或降低门槛。

## 14. 对话交接与执行提示词

逐卡在对话给出：卡号及状态；新增/修改文件；TC PASS/FAIL/NOT_RUN与命令/退出码/报告路径；前置既有失败；越界检查；下一卡。无需另建完成报告。用户明确要求运行手册与机器验收证据的文件已在白名单中，按卡更新即可。
可将下面提示词连同本书交给执行模型：

```text
执行 docs/任务书/草场任务书-105E-数字人工作台-阶段四-Vue工作台与交互闭环.md，从 C105E-01 开始，采用 AUTO_CHAIN。
先核对当前卡前置，再读 AGENTS.md、适用 DESIGN.md、任务书第0/9/13/14节和共享契约K14及该卡明确引用的其余K节。
只修改本卡与全局白名单的交集，保留现有工作区修改，不实现未定义的扩展。
每卡完成后真实运行列出的TC/V并汇报，再按依赖自动推进本阶段，不为普通本地步骤逐次等确认。
真实条件不具备如实标NOT_RUN，继续本书中无依赖的本地工作；不能自动部署、打开生产开关或把Fake当真实验收。
业务或资金决策冲突时给出精确阻塞点，不自己换方案。
```
