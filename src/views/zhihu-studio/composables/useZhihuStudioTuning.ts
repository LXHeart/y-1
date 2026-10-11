/**
 * 知乎创作台左栏调参（对位 xhs-studio useXhsStudioTuning）。
 *
 * - 风格目录（GENRE=领域 / STYLE=语气）复用服务端目录（/api/creation-style-skills），
 *   挂载即拉取一次；失败置 error 态供左栏显式 retry，前端不硬编码兜底。
 *   任务书 #62 已将风格三选向知乎开放（回答与文章两模式都有）。
 * - 目录按 applicablePlatforms 过滤：空 = 全平台通用；否则须含 'zhihu'。
 * - audience 读写 engine.brief.audience（真实端到端字段，随生成请求发出、随草稿持久化）。
 */
import { computed } from 'vue'
import type { ComputedRef, WritableComputedRef } from 'vue'
import type { useArticleCreation } from '../../../composables/useArticleCreation'
import type { CreationStyleSkillOption } from '../../../types/article-creation'

/** 目标读者（写入 brief.audience，可清空）——知乎读者画像三段。 */
export const ZHIHU_AUDIENCE_OPTIONS: readonly string[] = ['泛读者', '行业从业者', '学生 / 求职者']

export interface ZhihuStudioTuning {
  genreOptions: ComputedRef<CreationStyleSkillOption[]>
  styleOptions: ComputedRef<CreationStyleSkillOption[]>
  /** ''=未选；写入 brief.audience（空值移除字段，不发送）。 */
  audience: WritableComputedRef<string>
  /** 目录拉取失败后的显式重试（复用引擎 fetchStyleSkills 的防重入守卫）。 */
  retry: () => Promise<void>
}

/** 空 applicablePlatforms = 全平台通用；否则只在列出的平台可选。 */
function appliesToZhihu(option: CreationStyleSkillOption): boolean {
  const scope = option.applicablePlatforms
  return !scope || scope.length === 0 || scope.includes('zhihu')
}

export function useZhihuStudioTuning(article: ReturnType<typeof useArticleCreation>): ZhihuStudioTuning {
  // 挂载即拉取一次（fetchStyleSkills 内部 loading 防重入）；失败由左栏 error 态 + retry 兜。
  void article.fetchStyleSkills()

  const genreOptions = computed(() => article.styleSkillOptions.value.GENRE.filter(appliesToZhihu))
  const styleOptions = computed(() => article.styleSkillOptions.value.STYLE.filter(appliesToZhihu))

  /** 写 brief.audience：空值移除字段（不发送），避免空串覆盖语义。 */
  const audience = computed<string>({
    get: () => article.brief.value?.audience ?? '',
    set: (value) => {
      const next = value.trim()
      const brief = article.brief.value ?? { processingMode: 'create' as const, voice: { mode: 'none' as const } }
      article.brief.value = next
        ? { ...brief, audience: next }
        : { ...brief, audience: undefined }
    },
  })

  return { genreOptions, styleOptions, audience, retry: () => article.fetchStyleSkills() }
}
