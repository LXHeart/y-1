/**
 * 任务书 #101 C101-01：创作场景模板目录（contracts/creation-recipes.v1.json 单源）。
 *
 * 目录带版本与适用性（平台×形式×加工方式）；未知组合返回不可用原因，不静默切换平台、
 * 不回退到第一个模板。enabled=false 的模板不出现在可见集合，也不能生成（后续卡负责启用）。
 */
import recipesContract from '../../contracts/creation-recipes.v1.json'
import type { AiContentFormId, AiPlatformId } from '../types/ai-creation'
import type { CreationProcessingMode } from '../types/creation'
import type { RecipeId, TargetAspect, VisualStrategy } from '../types/creation-studio'

export const CREATION_RECIPES_CONTRACT_VERSION = recipesContract.version

export interface CreationRecipeDefinition {
  id: RecipeId
  version: string
  label: string
  enabled: boolean
  platformIds: readonly AiPlatformId[]
  contentForms: readonly AiContentFormId[]
  processingModes: readonly CreationProcessingMode[]
  minItems: number
  maxItems: number
  defaultAspect: TargetAspect | null
  supportedStrategies: readonly VisualStrategy[]
}

const RECIPE_IDS: readonly RecipeId[] = ['social-card-series', 'article-visuals', 'article-format', 'cover-only']
const STRATEGIES: readonly VisualStrategy[] = ['story', 'information', 'visual']
const ASPECTS: readonly TargetAspect[] = ['3:4', '9:16', '1:1', '16:9', '2.35:1']

function toDefinition(raw: (typeof recipesContract.recipes)[number]): CreationRecipeDefinition {
  const id = raw.id as RecipeId
  if (!RECIPE_IDS.includes(id)) throw new Error(`未知模板 ID: ${raw.id}`)
  return Object.freeze({
    id,
    version: raw.version,
    label: raw.label,
    enabled: raw.enabled,
    platformIds: Object.freeze([...raw.platformIds] as AiPlatformId[]),
    contentForms: Object.freeze([...raw.contentForms] as AiContentFormId[]),
    processingModes: Object.freeze([...raw.processingModes] as CreationProcessingMode[]),
    minItems: raw.minItems,
    maxItems: raw.maxItems,
    defaultAspect: (raw.defaultAspect as TargetAspect | null) ?? null,
    supportedStrategies: Object.freeze([...raw.supportedStrategies].filter(
      (strategy): strategy is VisualStrategy => STRATEGIES.includes(strategy as VisualStrategy),
    )),
  })
}

function validateContract(): void {
  if (!recipesContract.version) throw new Error('creation-recipes 契约缺少 version')
  const ids = new Set<string>()
  for (const raw of recipesContract.recipes) {
    if (ids.has(raw.id)) throw new Error(`模板 ID 重复: ${raw.id}`)
    ids.add(raw.id)
    if (!/^\d+\.\d+\.\d+$/.test(raw.version)) throw new Error(`模板 ${raw.id} 版本号非法: ${raw.version}`)
    if (raw.defaultAspect !== null && !ASPECTS.includes(raw.defaultAspect as TargetAspect)) {
      throw new Error(`模板 ${raw.id} 画幅非法: ${raw.defaultAspect}`)
    }
    if (!Number.isInteger(raw.minItems) || !Number.isInteger(raw.maxItems) || raw.minItems < 0 || raw.maxItems < raw.minItems) {
      throw new Error(`模板 ${raw.id} 数量边界非法`)
    }
  }
}

validateContract()

/** 目录全集（含 enabled=false；调用方一般用 listCreationRecipes）。 */
export const CREATION_RECIPES: readonly CreationRecipeDefinition[] = Object.freeze(
  recipesContract.recipes.map(toDefinition),
)

export interface CreationRecipeQuery {
  platform?: AiPlatformId | string
  contentForm?: AiContentFormId | string
  processingMode?: CreationProcessingMode | string
}

export function getCreationRecipe(id: string): CreationRecipeDefinition | null {
  return CREATION_RECIPES.find((recipe) => recipe.id === id) ?? null
}

/** API101-01 前端镜像：过滤平台／形式／加工方式后的可见（enabled）集合。 */
export function listCreationRecipes(query: CreationRecipeQuery = {}): CreationRecipeDefinition[] {
  return CREATION_RECIPES.filter((recipe) => {
    if (!recipe.enabled) return false
    if (query.platform != null && !recipe.platformIds.includes(query.platform as AiPlatformId)) return false
    if (query.contentForm != null && !recipe.contentForms.includes(query.contentForm as AiContentFormId)) return false
    if (query.processingMode != null && !recipe.processingModes.includes(query.processingMode as CreationProcessingMode)) return false
    return true
  })
}

export type CreationRecipeResolutionStatus = 'available' | 'unknown-id' | 'unknown-version' | 'disabled' | 'unsupported'

export interface CreationRecipeResolution {
  status: CreationRecipeResolutionStatus
  recipe: CreationRecipeDefinition | null
  /** 不可用原因（status !== 'available' 时非空），可直接展示给用户。 */
  reason: string | null
}

/**
 * 唯一合法定义或受控不可用原因（对应服务端 resolveRecipe 的 400 语义）；
 * 永不回退到第一个模板。
 */
export function resolveCreationRecipe(
  id: string,
  version: string | undefined,
  platform: string,
  contentForm: string,
  processingMode: string,
): CreationRecipeResolution {
  const recipe = getCreationRecipe(id)
  if (!recipe) {
    return { status: 'unknown-id', recipe: null, reason: `未知模板：${id}` }
  }
  if (version != null && version !== recipe.version) {
    return { status: 'unknown-version', recipe, reason: `模板 ${id} 不存在版本 ${version}（当前 ${recipe.version}）` }
  }
  if (!recipe.enabled) {
    return { status: 'disabled', recipe, reason: `模板「${recipe.label}」暂未开放` }
  }
  if (!recipe.platformIds.includes(platform as AiPlatformId)
    || !recipe.contentForms.includes(contentForm as AiContentFormId)
    || !recipe.processingModes.includes(processingMode as CreationProcessingMode)) {
    return { status: 'unsupported', recipe, reason: `模板「${recipe.label}」不适用于当前平台／加工方式` }
  }
  return { status: 'available', recipe, reason: null }
}
