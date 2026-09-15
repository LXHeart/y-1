/**
 * 任务书 #103 C103-21（TC103-21-05 结构和兼容）：五个 Java 热点类分层合约。
 *
 * 断言四件事：
 * 1) 原热点类行数达标——TaskController/CommerceService 采用 §13.4 执行期技术修订后的实际边界
 *    （engagement 端点移尽后剩余为申请/任务核心面；支付引擎+归因+目录无第三服务可去），
 *    其余三类达到任务书原始目标；
 * 2) W21 全部 NEW 拆分产物存在且 ≤600 行（新增类上限）；
 * 3) taskcatalog 包内所有控制器路由唯一（端点搬移不得产生重复映射）；
 * 4) 五大 SFC ≤800（AGENTS 体积门禁）且豁免清单四文件只减不增。
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { expect, test } from 'vitest'

const JAVA_ROOT = 'platform-java/services'

/** [文件, 行数上限, 说明] */
const ORIGINAL_CLASS_TARGETS: Array<[string, number, string]> = [
  [
    `${JAVA_ROOT}/marketplace-service/src/main/java/com/grassland/marketplace/taskcatalog/TaskController.java`,
    1450,
    '§13.4 修订：engagement 端点已移尽（EngagementLifecycle/SubmissionController），实际边界 1423',
  ],
  [
    `${JAVA_ROOT}/marketplace-service/src/main/java/com/grassland/marketplace/commerce/CommerceRepository.java`,
    800,
    '任务书原目标；目录方法+公共 record+共享行塑造助手',
  ],
  [
    `${JAVA_ROOT}/marketplace-service/src/main/java/com/grassland/marketplace/commerce/CommerceService.java`,
    770,
    '§13.4 修订：支付引擎/归因纠错/套餐目录/下单无第三服务可去，实际边界 760',
  ],
  [
    `${JAVA_ROOT}/intelligence-service/src/main/java/com/grassland/intelligence/creationstudio/plan/VisualPlanService.java`,
    800,
    '任务书原目标；wire record+§6.5 解析缺省',
  ],
  [
    `${JAVA_ROOT}/intelligence-service/src/main/java/com/grassland/intelligence/creationstudio/wechat/WechatDraftSyncService.java`,
    650,
    '任务书原目标；wire record+委托',
  ],
]

const NEW_CLASS_FILES = [
  `${JAVA_ROOT}/marketplace-service/src/main/java/com/grassland/marketplace/taskcatalog/EngagementLifecycleController.java`,
  `${JAVA_ROOT}/marketplace-service/src/main/java/com/grassland/marketplace/taskcatalog/EngagementSubmissionController.java`,
  `${JAVA_ROOT}/marketplace-service/src/main/java/com/grassland/marketplace/commerce/CommerceOrderCommandRepository.java`,
  `${JAVA_ROOT}/marketplace-service/src/main/java/com/grassland/marketplace/commerce/CommerceOrderQueryRepository.java`,
  `${JAVA_ROOT}/marketplace-service/src/main/java/com/grassland/marketplace/commerce/ConsumerRefundService.java`,
  `${JAVA_ROOT}/marketplace-service/src/main/java/com/grassland/marketplace/commerce/ConsumerRedemptionService.java`,
  `${JAVA_ROOT}/intelligence-service/src/main/java/com/grassland/intelligence/creationstudio/plan/VisualPlanBuildService.java`,
  `${JAVA_ROOT}/intelligence-service/src/main/java/com/grassland/intelligence/creationstudio/plan/VisualPlanRevisionService.java`,
  `${JAVA_ROOT}/intelligence-service/src/main/java/com/grassland/intelligence/creationstudio/wechat/WechatSyncPreparationService.java`,
  `${JAVA_ROOT}/intelligence-service/src/main/java/com/grassland/intelligence/creationstudio/wechat/WechatSyncExecutionService.java`,
]

/** AGENTS 体积门禁：五大 SFC 硬顶 800；豁免清单只减不增（基线为 AGENTS 登记值）。 */
const FIVE_SFC_CAP = 800
const SFC_TARGETS: Array<[string, number]> = [
  ['src/views/grassland/GrasslandWorkbench.vue', FIVE_SFC_CAP],
  ['src/ops/admin/AdminView.vue', FIVE_SFC_CAP],
  ['src/views/article/ArticleCreationView.vue', FIVE_SFC_CAP],
  ['src/views/video-production/VideoProductionView.vue', FIVE_SFC_CAP],
  ['src/views/image/ImageAnalysisView.vue', FIVE_SFC_CAP],
  // 豁免清单（AGENTS）：只减不增，上限为登记基线。
  ['src/views/disputes/DisputeDetailView.vue', 1024],
  ['src/views/ai-center/AiCreationCenter.vue', 964],
  ['src/components/MerchantKybCard.vue', 947],
  ['src/components/PrecedentLibrary.vue', 828],
]

/** 与 `wc -l` 同口径：结尾换行不计为一行。 */
function countLines(relative: string): number {
  const content = readFileSync(path.join(process.cwd(), relative)).toString()
  return content.endsWith('\n') ? content.split('\n').length - 1 : content.split('\n').length
}

test('TC103-21-05a 五个原热点类行数达到目标（含 §13.4 修订后的实际边界）', () => {
  for (const [file, max, note] of ORIGINAL_CLASS_TARGETS) {
    expect(existsSync(path.join(process.cwd(), file)), `${file} 应存在`).toBe(true)
    const lines = countLines(file)
    expect(lines, `${file} ≤${max}（${note}）`).toBeLessThanOrEqual(max)
  }
})

test('TC103-21-05b W21 全部 NEW 拆分产物存在且 ≤600 行', () => {
  expect(NEW_CLASS_FILES.length).toBeGreaterThanOrEqual(10)
  for (const file of NEW_CLASS_FILES) {
    expect(existsSync(path.join(process.cwd(), file)), `${file} 应存在`).toBe(true)
    const lines = countLines(file)
    expect(lines, `${file} 为新增类，须 ≤600`).toBeLessThanOrEqual(600)
  }
})

/** 从控制器源码提取「HTTP 方法 + 路径」路由表（类级 @RequestMapping 前缀 + 方法级 Mapping 注解）。 */
function extractRoutes(javaSource: string, fileName: string): Array<string> {
  const classPrefixMatch = javaSource.match(/@RequestMapping\(\s*"([^"]*)"/)
  const prefix = classPrefixMatch ? classPrefixMatch[1] : ''
  const routes: Array<string> = []
  const annotation = /@(Get|Post|Put|Delete|Patch)Mapping(?:\(([^)]*)\))?|@RequestMapping\(\s*(?:path\s*=\s*)?"([^"]*)"[^)]*method\s*=\s*RequestMethod\.([A-Z]+)/g
  let match: RegExpExecArray | null
  while ((match = annotation.exec(javaSource)) !== null) {
    if (match[1]) {
      const args = match[2] ?? ''
      const pathMatch = args.match(/(?:path\s*=\s*)?"([^"]*)"/)
      const routePath = pathMatch ? pathMatch[1] : ''
      routes.push(`${match[1].toUpperCase()} ${prefix}${routePath}`)
    } else if (match[4]) {
      routes.push(`${match[4]} ${prefix}${match[3] ?? ''}`)
    } else {
      throw new Error(`${fileName}: 无法解析的映射注解 @${match[0].slice(0, 40)}`)
    }
  }
  return routes
}

const TASKCATALOG_DIR = `${JAVA_ROOT}/marketplace-service/src/main/java/com/grassland/marketplace/taskcatalog`

test('TC103-21-05c taskcatalog 包内全部控制器路由唯一（端点搬移零重复）', () => {
  const controllers = readdirSync(path.join(process.cwd(), TASKCATALOG_DIR)).filter(
    (name) => name.endsWith('Controller.java'),
  )
  expect(controllers.length).toBeGreaterThanOrEqual(9)
  const seen = new Map<string, string>()
  const duplicates: Array<string> = []
  for (const name of controllers) {
    const source = readFileSync(path.join(process.cwd(), TASKCATALOG_DIR, name)).toString()
    for (const route of extractRoutes(source, name)) {
      if (seen.has(route)) {
        duplicates.push(`${route} 同时出现在 ${seen.get(route)} 与 ${name}`)
      } else {
        seen.set(route, name)
      }
    }
  }
  // 端点搬移后仍应有足量路由（防止解析器漏扫造成假绿）。
  expect(seen.size).toBeGreaterThanOrEqual(60)
  expect(duplicates, `重复路由：\n${duplicates.join('\n')}`).toEqual([])
})

test('TC103-21-05d 五大 SFC ≤800 且豁免清单只减不增', () => {
  for (const [file, cap] of SFC_TARGETS) {
    expect(existsSync(path.join(process.cwd(), file)), `${file} 应存在`).toBe(true)
    const lines = countLines(file)
    expect(lines, `${file} ≤${cap}`).toBeLessThanOrEqual(cap)
  }
})
