# OpenTalking 源码核验与数字人接入差距

> 核验日期：2026-09-21；性质：只读源码研究，不是运行测试。草场基线 `f2976aaeb329ee17e9e08a8997bffaabde78d3ac` + 当前既有未提交改动。关联[PRD v2](../产品/数字人工作台需求文档.md)、[#105总纲](../任务书/草场任务书-105-数字人工作台开发计划.md)。

> **2026-09-22实施口径修订**：下列上游源码事实保留；草场不采用任何本地模型部署，全部模型由第三方提供并复用现有模型配置。涉及原local候选的实施决定已由#105 v2.1/K14替代；本文不是新供应商API已核验的证据。

## 1. 核验方法与结论边界

通过 GitHub CLI 读取 main 的 commit、递归文件树及该提交的源代码归档，逐项查看 schema、路由、service、runner、provider、录制、时钟、依赖及对应测试；官方网页交叉核对仓库与模型许可说明。固定研究提交为 `8c739a5a6f114daf71aeace832a668c3ad60f536`，提交时间2026-09-04T01:49:54Z。不能把此日期当草场功能上线日期。

没有运行上游、安装Python环境/权重、调用真实provider或测量帧率。本次源文件下载仅用于阅读；未将第三方整个仓库混进产品目录。下列“有实现”不等于本项目已集成，“有测试文件”不等于本次测试通过。

## 2. 逐项证据及决定

| 编号 | 固定提交的一手源码/符号 | 核实事实 | 草场必须补齐 / 对应阶段 |
|---|---|---|---|
| OT01 | [architecture.md](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/docs/zh/docs/architecture.md) | Python API/Worker、Redis或内存总线；认证/TURN外部承担 | Vue/Java继续拥有业务权限；A/C/H |
| OT02 | [session.py](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/apps/api/schemas/session.py) `CreateSessionRequest` | agent_enabled与knowledge_enabled默认true，user_id可由调用者填 | 包装器不挂原生路由，显式关闭agent/memory/knowledge；A/D |
| OT03 | [sessions.py](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/apps/api/routes/sessions.py) `speak_audio_stream_ws` | 首帧meta，二进制PCM s16le/mono/16kHz，end结束；queue无该路由内显式maxsize，整体120秒超时 | 自建有鉴权、有帧/总量限制、背压的WS；C/D/E |
| OT04 | [STT adapter](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/opentalking/providers/stt/openai_compatible/adapter.py) `transcribe_pcm_queue` | 收完整段后写NamedTemporaryFile WAV，再调识别接口 | 首期草场内存WAV调用已有Java provider；不能声称所有STT是真流式；D |
| OT05 | [LLM adapter](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/opentalking/providers/llm/openai_compatible/adapter.py) `chat_stream` | 仅读取delta.content，无用量终帧；API key在实例中 | 用Java计量桥，provider长期key不发给Python；D |
| OT06 | [TTS adapter](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/opentalking/providers/tts/openai_compatible/adapter.py) `synthesize_stream` | PCM格式走HTTP流；WAV等分支先取得完整响应，再切AudioChunk | 测试响应是否真正分块；码率/采样率显式合同，不把切块当低首音；D |
| OT07 | [task_consumer.py](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/opentalking/runtime/task_consumer.py) `_create_runner` | 从get_settings取得LLM参数、runner分local/mock/remote，TTS工厂也读取配置 | 有限补丁注入每会话Binding；不改全局.env切用户；A/D |
| OT08 | [session_service.py](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/apps/api/services/session_service.py) `speak` | SET NX receipt、payload hash、24h；pending→RPUSH之间崩溃不自动重放；每次speak先interrupt | 保留上游已有去重；增加草场持久操作状态、原经济键、确定失败/未知分类；C/D |
| OT09 | [events.py](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/apps/api/routes/events.py) `session_events` | pub/sub→SSE，没有id字段与历史回放 | Java有序状态事实，内存内容回放有界；断流快照恢复；C |
| OT10 | [session_store.py](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/opentalking/core/session_store.py) `set_session_state` | 非终态persist，closed/error才600秒TTL | 草场独立租约与回收器；upstream TTL不是失联回收；C/G |
| OT11 | [recording.py](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/opentalking/pipeline/recording/recording.py) `export_flashtalk_recording` | JPG帧经OpenCV VideoWriter/mp4v写MP4，无输出音轨混流 | 不能直接复用为带声音录制；F新增输出AV分支与ffprobe验证 |
| OT12 | [session_store.py](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/opentalking/core/session_store.py) `apply_flashtalk_recording_start` | 开始会清除该session既有录制文件 | 独立recordingId分段，不以session目录覆盖上一段；F |
| OT13 | [clock.py](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/opentalking/streaming/clock.py) `ProgramClock`；[manager.py](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/opentalking/streaming/manager.py) | 连续节目时钟、独立音视频branch队列，可统计丢帧 | 复用时钟/分流机制；录制队列溢出明确partial/failure，不静默漏音；D/F |
| OT14 | [synthesis_runner.py](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/opentalking/pipeline/speak/synthesis_runner.py) `FlashTalkRunner` | 名称虽叫FlashTalk，工厂也给mock/QuickTalk等使用；有program与WebRTC路径 | 依据工厂调用链，不能按类名假定只支持FlashTalk；D |
| OT15 | [backends.py](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/opentalking/providers/synthesis/backends.py) | 实际resolver接受mock/local/direct_ws/omnirt；接口文件中“local removed”注释与实际实现不一致 | 以resolver+运行合同为准，不只抄接口docstring；A |
| OT16 | [pyproject.toml](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/pyproject.toml) | 基础依赖有insightface/transformers/lightrag/mem0；多项宽版本约束 | A独立Python3.11最小编排/媒体依赖与import验证；不照搬重模型依赖，禁止本地推理与记忆模块 |
| OT17 | [timing.py](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/opentalking/runtime/timing.py) `SpeechTiming` | summary可包含text_preview | 适配补丁删除内容预览；检测日志与trace无提示词/音频/key；A/G |
| OT18 | [Mock文档](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/docs/zh/avatar_models/mock.md) | mock指渲染；示例仍配置LLM/STT key | A创建Fake STT/LLM/TTS和出站拒绝；CPU demo不等于无收费 |
| OT19 | [QuickTalk部署](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/docs/zh/model-deployment/quicktalk/local.md)；[Mac说明](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/docs/zh/model-deployment/quicktalk/apple-silicon.md) | 权重清单含QuickTalk/HuBERT/辅助检测；Mac文档区分流程验证与稳定实时输出 | v2.1不采用本地候选；全部渲染调用现有控制面配置的第三方服务，见K14；A/H |
| OT20 | [框架许可](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/LICENSE)、[Wav2Lip](https://github.com/Rudrabha/Wav2Lip)、[InsightFace](https://github.com/deepinsight/insightface) | 框架Apache-2.0不自动涵盖模型/权重许可 | 保留上游许可研究；草场不安装权重，真实开放核验第三方服务使用条款及资产授权 |

## 3. 当前草场代码带来的额外修正

| 本地锚点 | 本次发现 | v2决定 |
|---|---|---|
| `ai/byok/ByokRoutingService.resolveProvider` | 个人已有platform/own总开关；own缺key直接拒绝，不是个人key优先后平台兜底 | PRD与D卡遵循当前行为 |
| `ai/run/AiExecutionService.reserveAndCreateRun` | 个人BYOK免个人模型预算；仍记录ai_run | 纠正v1笼统“BYOK入预算”表述，资源时限独立 |
| `speech/SpeechRecognitionProvider.Command` | 已接受byte[]音频和ProviderInvocation | 内存WAV复用provider，避开上传/持久转写任务 |
| `ai/run/TextCompletionClient.streamMessages` / `ai/ChatChunk` | 当前流只返回content；没有供应商usage终帧 | D卡增加新计量流入口，旧接口保持兼容 |
| `ai/run/AiExecutionService.prepareExecution` | 每次生成新operation UUID | D卡新增稳定operation+原子prepared绑定入口，不能只在前端防连点 |
| `ai/run/AiRunRepository.findByOperationIdAndOwner` | 有安全回读，但现有operation索引不是唯一约束 | dh_invocation唯一键+原子绑定限制新业务，不全局重写旧ai_run |
| `ai/controlplane/CreatePlatformModelRequest` / `src/types/ai-control-plane.ts` | 已有voice/video_tts平台能力，BYOK列表不含语音 | 首期文本按个人来源、语音固定平台，分项展示 |
| `videoproduction/TtsWorker` | 现有video_tts使用feature=null，由平台承担配音成本 | 数字人TTS/试听/开场白复用补贴口径，不能误套视频收费键；记录成本并设试点上限 |
| `nginx.conf` AI server `listen 82` | 当前Permissions-Policy为microphone=()，会拦浏览器收音 | E06只对AI入口改microphone=(self)，H01验最终页面响应头/HTTPS；80/81维持原限制 |
| `V87__preserve_organization_lifecycle_ownership.sql` | 当前工作区已存在V87，#104有实施中修改 | 新迁移按当时最大版本顺延，禁止复用/覆盖V87；不把#104索引旧状态当未开始事实 |

## 4. 明确没有得到的证据

原研究没有真实本地模型或供应商性能证据；v2.1不再要求本地模型/GPU，当前仍缺第三方服务配置与协议实测、首音/同步/帧率、移动实机、带声音录制成片、生产TURN网络、真实费用核销证据。阶段任务书用明确的条件实验/开放门禁承接；缺证不能写PASS，也不应阻止无外部资源依赖的合同和Fake实现。
