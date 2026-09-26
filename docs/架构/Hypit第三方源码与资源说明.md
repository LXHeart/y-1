# Hypit 第三方源码与资源说明

> 任务书 #107-3 C107-24 文档收口。上游契约：`platform-hypit/upstream-manifest.json`；
> 校验入口：`bash scripts/acceptance/verify-107-upstream.sh`（V01，无网络源码校验）。

## 1. 上游来源与固定版本

| 项 | 值 |
|---|---|
| 上游项目 | Hypit（视频克隆引擎，Apache-2.0 主体，个别子包许可见 §3） |
| 固定版本 | 0.2.13 |
| 固定 commit | `2c320059`（upstream-manifest.json 逐包记录 hash） |
| 存放位置 | `platform-hypit/upstream/`（**只读**；一切修改必须走补丁） |
| 生成引擎 G | `platform-hypit/.generated/hypit/`（`scripts/acceptance/build-107-engine.sh` 产物） |

补丁目录：`platform-hypit/patches/`。升级 Hypit 版本不在 #107 范围内；升级须重放全部
补丁并重跑 V01～V07。

## 2. 补丁面（截至 #107-3）

| 补丁 | 内容 | 理由 |
|---|---|---|
| 0001 | Studio 子路径前缀无感 | Studio 会话挂载在 `/studio/<sessionId>/` 下（票据路径） |
| 0002 | 媒体代次帧门 + media gate 钩子 | 瞬时 disconnected 交还租约、持续 failed 才关 session（C105D） |
| 0003 | G 构建期修正 | 与 y-1 构建管线（pnpm→node test runner）兼容 |

补丁规则：只修缺陷/接缝，不携带产品语义；每个补丁在 `build-107-engine.sh` 重放，
replay 校验 hash（V03）。

## 3. 第三方许可要点

- **PyAV + libx264**：输出 AV 录制经 PyAV wheel 分发（libx264 为 GPL，经 wheel 分发不传染
  宿主仓库）；`license-manifest` 已登记许可行（C105F-02）。
- **浏览器**：capture 用上游 `packages/browser-capture` manifest 的
  `hypit.captureBrowser.version`（153.0.8010.12）；render 用
  `packages/provider-hyperframes-local` 的 `hypit.renderBrowser.version`
  （152.0.7928.2，Chrome Headless Shell）。两套各自原生 installer/cache/probe，
  不能仅装 capture Chrome 就声称 render ready。
- **模型/语料**：WhisperX ASR 模型、对齐权重、NLTK 句子数据——下载是**运营者准备动作**
  （prepare-programs.sh 显式步骤），推理永不联网；仓库不含模型二进制。

## 4. 资源边界

- 上游源码全入库 ≠ 模型/字体包/浏览器天然离线可用；首次 prepare 需要外网，
  下载项/日志落 `install.log`；网络不可用时 status 不得写 ready。
- 真实第三方渲染/真实模型服务（HypiHub 等）属 live 层：需运营者凭据与预算授权，
  入口 `scripts/acceptance/verify-107-live.sh`；本地验收一律走无 key 链 + 本地模板。
- y-1 侧不向上游回传任何用户数据；broker 是唯一持工作区状态的组件，runner 只见本槽输入。

## 5. 审计入口

```bash
bash scripts/acceptance/verify-107-upstream.sh     # manifest/hash/补丁重放校验
ls platform-hypit/patches/                          # 补丁面清单
cat platform-hypit/upstream-manifest.json           # 逐包来源与固定 hash
```
