<template>
  <div class="zhihu-studio gl-field">
    <ZhihuStudioTopBar
      :save-state="autosave.saveState.value"
      :conflict="autosave.conflictNotice.value"
      :readonly="autosave.readonly.value"
      :draft-title="draftTitle"
      :content-mode="engine.contentMode.value"
      :task-question-locked="autosave.taskQuestionLocked.value"
      @back="goCreationCenter"
      @save-draft="onSaveDraft"
      @reset="resetSession"
      @retry="autosave.retry"
      @reload="autosave.reloadRemote"
      @set-mode="requestContentMode"
    />

    <ZhihuStudioStepsBar
      :steps="ZHIHU_STUDIO_STEPS"
      :current="steps.current.value"
      :reached="steps.reached.value"
      :pick-label="answerMode ? '问题' : '选题'"
      @select="steps.go"
    />

    <div v-if="toast" class="zhihu-toast" role="status" aria-live="polite">{{ toast }}</div>

    <div class="zhihu-layout">
      <aside class="zhihu-side">
        <ZhihuSideConfigPanel
          :answer-mode="answerMode"
          :question="engine.question.value"
          :question-ref="engine.questionRef.value"
          :question-locked="autosave.taskQuestionLocked.value"
          :topic="engine.topic.value"
          :genre-options="tuning.genreOptions.value"
          :style-options="tuning.styleOptions.value"
          v-model:genre="engine.genre.value"
          v-model:style="engine.style.value"
          v-model:audience="tuning.audience.value"
          :loading="engine.styleSkillsLoading.value"
          :error="engine.styleSkillsError.value"
          :balance="creditBalance"
          :balance-error="credits.error.value"
          :format-summary="format.formatRuleSummary.value"
          :can-generate="canGenerate"
          :generating="generating"
          :generate-label="generateLabel"
          @update:question="engine.setQuestion"
          @update:topic="onTopicInput"
          @retry="tuning.retry"
          @generate="onGenerate"
          @request-login="emit('request-login')"
        />
      </aside>

      <section class="zhihu-stage glass-card">
        <template v-if="steps.current.value === 'pick'">
          <!-- 选题步中栏：创作简报（表达身份/已确认经历/商业关系）与我的文风（VOICE_PLATFORMS
               含 zhihu，genre 随形态分叉 answer|article——旧视图同款接线）。 -->
          <CreationBriefEditor
            v-model="engine.brief.value"
            :disabled="autosave.readonly.value"
            :before-role-change="voicePanel?.confirmDiscard"
          />
          <VoiceProfilePanel
            ref="voicePanel"
            v-model="engine.brief.value"
            platform="zhihu"
            :genre="answerMode ? 'answer' : 'article'"
            :draft-id="autosave.draftId.value"
            :disabled="autosave.readonly.value"
            :original="engine.content.value"
            :edited="engine.content.value"
          />
        </template>
        <template v-else-if="steps.current.value === 'generate'">
          <ZhihuTitleCandidates />
          <ZhihuPaperBody @enter-proof="steps.go('proof')" />
          <ZhihuTopicTagsEditor v-if="!answerMode" />
          <ZhihuCompliancePanel mode="inline" />
        </template>
        <template v-else-if="steps.current.value === 'proof'">
          <ZhihuCompliancePanel mode="check" />
          <ZhihuProofChecklist @go-edit="() => steps.go('generate')" />
        </template>
        <template v-else-if="steps.current.value === 'publish'">
          <!-- 发布步双态：配图（stage='images'，未 finish）→ 交付导出（completed）。 -->
          <ZhihuImagesPanel v-if="!engine.completed.value" @open-lightbox="openLightbox" />
          <ZhihuPublishPanel v-else />
        </template>
      </section>

      <aside class="zhihu-preview">
        <ZhihuPaperPreview />
      </aside>
    </div>

    <ArticleLightbox :src="lightboxSrc" @close="closeLightbox" />
  </div>
</template>

<script setup lang="ts">
/**
 * 知乎专属创作台视图骨架：三栏布局（左选题台 / 中按步工作区 / 右页面预览）+ 四步显隐 +
 * 顶栏回答/文章形态切换。纯装配：引擎/持久化/步骤机在 provideZhihuStudioContext 内组合并
 * provide。配图在发布步（stage='images' → publish 自动跳转），与小红书跳配图的差异点。
 */
import { computed, onMounted, ref } from 'vue'
import { useCredits } from '../../composables/useCredits'
import ArticleLightbox from '../article/components/ArticleLightbox.vue'
import CreationBriefEditor from '../../components/CreationBriefEditor.vue'
import VoiceProfilePanel from '../../components/creation/VoiceProfilePanel.vue'
import { provideZhihuStudioContext } from './composables/useZhihuStudioContext'
import ZhihuStudioTopBar from './components/ZhihuStudioTopBar.vue'
import ZhihuStudioStepsBar from './components/ZhihuStudioStepsBar.vue'
import ZhihuSideConfigPanel from './components/ZhihuSideConfigPanel.vue'
import ZhihuTitleCandidates from './components/ZhihuTitleCandidates.vue'
import ZhihuPaperBody from './components/ZhihuPaperBody.vue'
import ZhihuTopicTagsEditor from './components/ZhihuTopicTagsEditor.vue'
import ZhihuCompliancePanel from './components/ZhihuCompliancePanel.vue'
import ZhihuProofChecklist from './components/ZhihuProofChecklist.vue'
import ZhihuImagesPanel from './components/ZhihuImagesPanel.vue'
import ZhihuPublishPanel from './components/ZhihuPublishPanel.vue'
import ZhihuPaperPreview from './components/ZhihuPaperPreview.vue'
import { ZHIHU_STUDIO_STEPS } from './types'
import type { CreationHandoff } from '../../types/ai-creation'

const props = defineProps<{
  creationHandoff?: CreationHandoff | null
}>()

const emit = defineEmits<{
  'open-view': [view: 'ai-center']
  'request-login': []
}>()

const {
  engine, autosave, format, steps, tuning, toast, notify, goCreationCenter,
  answerMode, requestContentMode, resetSession,
} = provideZhihuStudioContext({
  handoff: () => props.creationHandoff,
  goCreationCenter: () => emit('open-view', 'ai-center'),
})

/** 积分：真实余额（null=未加载；错误文案由 store error 区分），挂载时幂等拉取一次。 */
const credits = useCredits()
onMounted(() => { void credits.loadBalance() })
const creditBalance = computed(() => credits.balance.value === null ? null : credits.currentBalance.value)

/** 左栏主按钮：pick 步=生成候选（回答按问题、文章按选题）；generate 步=重新生成。 */
const generating = computed(() => engine.titlesLoading.value || engine.outlineLoading.value || engine.contentLoading.value)
const canGenerate = computed(() => {
  if (steps.current.value !== 'pick') return true
  return answerMode.value ? engine.question.value.trim().length > 0 : engine.topic.value.trim().length > 0
})
const generateLabel = computed(() => steps.current.value === 'pick'
  ? (answerMode.value ? '✦ AI 生成回答' : '✦ AI 生成文章')
  : '⟳ 重新生成')

const draftTitle = computed(() => (answerMode.value
  ? engine.question.value : (engine.selectedTitle.value || engine.topic.value)).trim().slice(0, 60))

/**
 * 选题输入按形态分叉写引擎：回答模式的补充说明与文章模式的选题方向共用 engine.topic，
 * 但回答模式输入时保持「问题步」语义（旧视图 setTopic 在 question 阶段会 reset 全文，
 * 这里仅写值不重置——恢复/切换场景由 context 统一处理）。
 */
function onTopicInput(value: string): void {
  engine.topic.value = value
}

async function onGenerate(): Promise<void> {
  await engine.fetchTitles()
  if (engine.titles.value.length > 0) steps.go('generate')
  else if (engine.error.value) notify(engine.error.value)
}

async function onSaveDraft(): Promise<void> {
  const saved = await autosave.flush()
  notify(saved ? '草稿已保存' : '保存失败，请稍后重试')
}

/** 配图/预览点击放大（组件经 emit 上抛）。 */
const lightboxSrc = ref('')
/** 我的文面板引用：换表达身份前经 confirmDiscard 确认丢弃文风（对齐旧视图接线）。 */
const voicePanel = ref<InstanceType<typeof VoiceProfilePanel> | null>(null)
function openLightbox(src: string): void {
  lightboxSrc.value = src
}
function closeLightbox(): void {
  lightboxSrc.value = ''
}
</script>
