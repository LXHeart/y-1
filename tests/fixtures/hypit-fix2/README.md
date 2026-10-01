# hypit-fix2 验收 fixture 与契约样本（107-fix-2 / C107F2-01）

本目录是任务书 [107-fix-2](../../../docs/任务书/草场任务书-107-fix-2-Hypit全链路缺陷修复与真实交付验收.md) 的正式验收输入：干净克隆即可重建，不依赖 `test-artifacts/` 下未入库脚本。

## 内容

| 文件 | 作用 | 消费方 |
| --- | --- | --- |
| `contract-samples.json` | 跨层契约样本（Java Controller/broker 文件/SSE/Build 两维）+ 六类前端反例 + 五类后端探针，全部带 `status`（current-wrong/current-ok/target）与来源标记 | C01 反例可发现断言；C03/10/12/13/20/24/25/26/27/28 契约回归输入 |
| （运行期生成）`media/ package/ accounts/ manifest.json` | 由 `scripts/acceptance/fixtures/hypit-fix2-fixtures.mjs` 生成 | C08/C37/C38 真实媒体与合成账号 |

## 生成与可重现

```bash
node scripts/acceptance/fixtures/hypit-fix2-fixtures.mjs --dest /tmp/fix2-fx --seed 10702
node scripts/acceptance/fixtures/hypit-fix2-fixtures.mjs --dest /tmp/fix2-fx2 --seed 10702   # hash 必须与上一次逐字节一致
```

- seed 固定 **10702**（§9.3）；manifest 时间戳固定、PRNG/UUID/编码全部确定性。
- PNG/WAV/ZIP/账号元数据为纯 Node 编码，任何环境逐字节一致；MP4 在有 ffmpeg 的构建上真实编码（同构建可重现），缺 ffmpeg 时为占位字节并标注 `kind=placeholder-video`。需要可解码媒体的卡（C08/C37/C38）必须加 `--require-encoder`（缺环境非零，不退化为占位通过）。
- `manifest.providerCalls` 恒 0：fixture 生成不访问网络、不调用 Provider。

## fixture 与实际的边界（必读）

1. **counterExamples 的 `observed` 是审计期冻结的旧行为记录**（2026-09-27 审计，基线 `98dc80cd`，以合成数据在隔离环境复现），不是线上事故，也不是目标行为；每条都有 `targetRule` 与责任卡。禁止把 `observed` 样本当作目标契约冻结——那等于把缺陷写进规范。
2. **外部模型 fixture 只替最后一跳**：分析/编写/生成的编排、传参、消费链走真实平台执行环；fixture 不能证明商业模型质量，也不免除本地原生渲染/浏览器/PG 验收（D-12）。
3. **账号是合成的**：owner A/B、operator 的 UUID 由 seed 派生；密码运行期注入，绝不写入 fixture、任务书或日志。
4. **媒体是本地合成的**：3 秒移动元素片、12 秒参考片、有声/无声 WAV、PNG/二进制样本，不下载真实用户素材。
5. `status=current-ok` 的样本（错误信封、202 受理信封、broker 文件 hash 字段）是当前实现中目标保留的行为；`current-wrong` 由责任卡按 `target` 修正。

## 六类前端反例 / 五类后端探针索引

| 反例 | 审计 finding | 责任卡 |
| --- | --- | --- |
| FE-01 生成按钮无 POST | F06 | C107F2-18 |
| FE-02 变体 cancel 未调用服务端 | F13 | C107F2-27 |
| FE-03 outputs 信封错配 | F11 | C107F2-12 |
| FE-04 baseHash 契约漏读 | F09 | C107F2-10 |
| FE-05 迟到响应跨工程覆盖 | F08 | C107F2-11 |
| FE-06 评论修改整文件替换 | F10 | C107F2-25 |
| BE-01 Studio 复用/票据核销错误 | F19 | C107F2-20 |
| BE-02 导入二进制损坏 | F30 | C107F2-28 |
| BE-03 非法 Run 接受 | F30 | C107F2-28 |
| BE-04 Feedback 部分写 | F32 | C107F2-24 |
| BE-05 runner env 忽略 | F02 | C107F2-03 |

可执行组件级回归测试（挂载真实 Vue 组件/mock fetch 断言网络行为）由各责任卡的 `fix2-cXX.test.ts` 落地；C01 只固化输入与「旧行为必须被目标规则判失败」的可发现性断言。
