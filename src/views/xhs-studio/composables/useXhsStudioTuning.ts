/**
 * 小红书创作台左栏调参（阶段 0 骨架，方案 §4.1）。
 *
 * - 风格目录（GENRE=赛道 / STYLE=语气 / TITLE_FORMULA=标题套路）复用服务端目录
 *   （/api/creation-style-skills），挂载即拉取一次；失败置 error 态供左栏显式 retry，
 *   前端不硬编码兜底（useArticleCreation 注释明确）。
 * - 目录按 applicablePlatforms 过滤：空 = 全平台通用；否则须含 'xiaohongshu'。
 *   平台恒定 xiaohongshu，无需换平台清空选择。
 * - audience 读写 engine.brief.audience（真实端到端字段，随生成请求发出、随草稿持久化）。
 */
import { computed } from 'vue'
import type { ComputedRef, WritableComputedRef } from 'vue'
import type { useArticleCreation } from '../../../composables/useArticleCreation'
import type { CreationStyleSkillOption } from '../../../types/article-creation'

/** 【cfg-audience】目标人群三段（写入 brief.audience，可清空）。 */
export const XHS_AUDIENCE_OPTIONS: readonly string[] = ['学生党', '职场人', '宝妈']

export interface XhsStudioTuning {
  genreOptions: ComputedRef<CreationStyleSkillOption[]>
  styleOptions: ComputedRef<CreationStyleSkillOption[]>
  formulaOptions: ComputedRef<CreationStyleSkillOption[]>
  /** ''=未选；写入 brief.audience（空值移除字段，不发送）。 */
  audience: WritableComputedRef<string>
  /** 目录拉取失败后的显式重试（复用引擎 fetchStyleSkills 的防重入守卫）。 */
  retry: () => Promise<void>
}

/** 空 applicablePlatforms = 全平台通用；否则只在列出的平台可选。 */
function appliesToXiaohongshu(option: CreationStyleSkillOption): boolean {
  const scope = option.applicablePlatforms
  return !scope || scope.length === 0 || scope.includes('xiaohongshu')
}

export function useXhsStudioTuning(article: ReturnType<typeof useArticleCreation>): XhsStudioTuning {
  // 挂载即拉取一次（fetchStyleSkills 内部 loading 防重入）；失败由左栏 error 态 + retry 兜。
  void article.fetchStyleSkills()

  const genreOptions = computed(() => article.styleSkillOptions.value.GENRE.filter(appliesToXiaohongshu))
  const styleOptions = computed(() => article.styleSkillOptions.value.STYLE.filter(appliesToXiaohongshu))
  const formulaOptions = computed(() => article.styleSkillOptions.value.TITLE_FORMULA.filter(appliesToXiaohongshu))

  /** 写 brief.audience：空值移除字段（不发送），避免空串覆盖语义。 */
  const audience = computed<string>({
    get: () => article.brief.value?.audience ?? '',
    set: (value: string) => {
      const next = value.trim()
      const brief = article.brief.value ?? { processingMode: 'create' as const, voice: { mode: 'none' as const } }
      article.brief.value = next
        ? { ...brief, audience: next }
        : { ...brief, audience: undefined }
    },
  })

  return { genreOptions, styleOptions, formulaOptions, audience, retry: () => article.fetchStyleSkills() }
}
