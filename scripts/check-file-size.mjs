#!/usr/bin/env node
// 体积门禁（任务书 #91 G1，D-09）。
// 规则一：src/**/*.vue 超过 800 行即 exit 1，打印文件与行数。
// 规则二：豁免清单四文件只减不增，超过豁免基线（当前行数）即红。
// 规则三：src/**/composables/*.ts 超过 500 行仅打 WARN，不阻断（超标 composable 留待后续批次）。
// 接入 npm run lint 末步；G1 须在五文件拆分完成后执行，避免拆分中被自己拦截。
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'

const ROOT = new URL('..', import.meta.url).pathname
const SRC = join(ROOT, 'src')
const VUE_LIMIT = 800
const COMPOSABLE_WARN_LIMIT = 500

// 豁免清单（D-09：只减不增，值为当前基线行数，超出即红）。
const EXEMPT = {
  'src/views/disputes/DisputeDetailView.vue': 1024,
  'src/views/ai-center/AiCreationCenter.vue': 964,
  'src/components/MerchantKybCard.vue': 947,
  'src/components/PrecedentLibrary.vue': 828,
}

function walk(dir) {
  const out = []
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    const st = statSync(full)
    if (st.isDirectory()) out.push(...walk(full))
    else out.push(full)
  }
  return out
}

let failed = false
const warn = []

for (const file of walk(SRC)) {
  const rel = relative(ROOT, file)
  // 与 wc -l 同口径：按换行符计数
  const lines = readFileSync(file, 'utf8').split('\n').length - 1
  if (rel.endsWith('.vue')) {
    if (rel in EXEMPT) {
      if (lines > EXEMPT[rel]) {
        console.error(`[file-size] 豁免文件回涨（只减不增红线）: ${rel} ${lines} 行 > 基线 ${EXEMPT[rel]}`)
        failed = true
      }
      continue
    }
    if (lines > VUE_LIMIT) {
      console.error(`[file-size] 超限: ${rel} ${lines} 行 > ${VUE_LIMIT}`)
      failed = true
    }
  } else if (rel.includes('/composables/') && rel.endsWith('.ts') && lines > COMPOSABLE_WARN_LIMIT) {
    warn.push(`${rel} ${lines} 行 > ${COMPOSABLE_WARN_LIMIT}（WARN，后续批次处理）`)
  }
}

for (const w of warn) console.warn(`[file-size] ${w}`)
if (failed) {
  console.error('[file-size] 门禁未通过（.vue 超 800 硬顶或豁免回涨）')
  process.exit(1)
}
console.log('[file-size] 门禁通过：src/**/*.vue 全部不超 800 行，豁免四文件未回涨')
