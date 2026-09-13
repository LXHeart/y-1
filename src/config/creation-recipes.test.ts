import { describe, expect, test } from 'vitest'
import {
  CREATION_RECIPES,
  CREATION_RECIPES_CONTRACT_VERSION,
  getCreationRecipe,
  listCreationRecipes,
  resolveCreationRecipe,
} from './creation-recipes'

/**
 * 任务书 #101 C101-01：模板目录契约（contracts/creation-recipes.v1.json 单源）。
 * 覆盖 TC101-001（可见集合过滤）与 TC101-003（未知版本／非法组合不降级）的前端侧。
 */
describe('creation-recipes 目录', () => {
  test('四模板齐全且版本均为 1.0.0', () => {
    expect(CREATION_RECIPES.map((recipe) => recipe.id)).toEqual([
      'social-card-series',
      'article-visuals',
      'article-format',
      'cover-only',
    ])
    expect(CREATION_RECIPES.every((recipe) => recipe.version === '1.0.0')).toBe(true)
    expect(CREATION_RECIPES_CONTRACT_VERSION).toBeTruthy()
  })

  test('TC101-001：平台／形式／模式过滤后的可见集合准确', () => {
    // C101-14/16 起三个新模板已启用（任务书里程碑）；可见集合按契约平台矩阵过滤。
    expect(listCreationRecipes({ platform: 'xiaohongshu', contentForm: 'graphic' }).map((r) => r.id))
      .toEqual(['social-card-series', 'cover-only'])
    expect(listCreationRecipes({ platform: 'xiaohongshu', contentForm: 'video' })).toEqual([])
    expect(listCreationRecipes({ platform: 'wechat-official', contentForm: 'graphic' }).map((r) => r.id))
      .toEqual(['article-visuals', 'article-format', 'cover-only'])
    expect(listCreationRecipes({ platform: 'wechat-official', contentForm: 'graphic', processingMode: 'adapt' }).map((r) => r.id))
      .toEqual(['article-visuals', 'cover-only'])
    expect(listCreationRecipes({ platform: 'douyin', contentForm: 'graphic', processingMode: 'format' }).map((r) => r.id))
      .toEqual(['cover-only'])
    expect(listCreationRecipes({ platform: 'douyin', contentForm: 'graphic', processingMode: 'adapt' }).map((r) => r.id))
      .toEqual(['social-card-series', 'cover-only'])
    // 大众点评／朋友圈不在任何模板适用范围（保留原有专用流程）
    expect(listCreationRecipes({ platform: 'dianping' })).toEqual([])
    expect(listCreationRecipes({ platform: 'moments' })).toEqual([])
  })

  test('TC101-003：未知版本／非法组合返回不可用原因，不回退第一个模板', () => {
    const unknownVersion = resolveCreationRecipe('social-card-series', '9.9.9', 'xiaohongshu', 'graphic', 'create')
    expect(unknownVersion.status).toBe('unknown-version')
    expect(unknownVersion.recipe?.id).toBe('social-card-series')
    expect(unknownVersion.reason).toContain('9.9.9')

    const unknownId = resolveCreationRecipe('infographic', undefined, 'xiaohongshu', 'graphic', 'create')
    expect(unknownId.status).toBe('unknown-id')
    expect(unknownId.recipe).toBeNull()

    // 小红书 graphic + format：social-card-series 不支持 format → 不合格而非静默换模板
    const unsupported = resolveCreationRecipe('social-card-series', undefined, 'xiaohongshu', 'graphic', 'format')
    expect(unsupported.status).toBe('unsupported')
    expect(unsupported.recipe).not.toBeNull()

    const available = resolveCreationRecipe('social-card-series', '1.0.0', 'xiaohongshu', 'graphic', 'create')
    expect(available.status).toBe('available')
    expect(available.recipe?.id).toBe('social-card-series')
    expect(available.reason).toBeNull()
  })

  test('全部四模板已启用（C101-14/16 里程碑）；平台不适用仍不可用', () => {
    expect(CREATION_RECIPES.filter((recipe) => recipe.enabled).map((recipe) => recipe.id))
      .toEqual(['social-card-series', 'article-visuals', 'article-format', 'cover-only'])
    // article-visuals 不适用小红书 → unsupported（不静默换模板）
    const unsupportedPlatform = resolveCreationRecipe('article-visuals', undefined, 'xiaohongshu', 'graphic', 'adapt')
    expect(unsupportedPlatform.status).toBe('unsupported')
    // article-format 只支持 format 模式
    const wrongMode = resolveCreationRecipe('article-format', undefined, 'wechat-official', 'graphic', 'create')
    expect(wrongMode.status).toBe('unsupported')
  })

  test('article-format 无图片参数（defaultAspect=null、0 项、无策略）', () => {
    const recipe = getCreationRecipe('article-format')
    expect(recipe).not.toBeNull()
    expect(recipe?.defaultAspect).toBeNull()
    expect(recipe?.minItems).toBe(0)
    expect(recipe?.maxItems).toBe(0)
    expect(recipe?.supportedStrategies).toEqual([])
  })

  test('目录不含 prompt 或密钥字段（公开响应约束）', () => {
    const contract = JSON.stringify(CREATION_RECIPES)
    expect(contract).not.toMatch(/prompt|secret|token|apiKey/i)
  })
})
