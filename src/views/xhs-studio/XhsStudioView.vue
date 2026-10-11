<template>
  <div class="xhs-studio gl-field">
    <XhsStudioTopBar
      :save-state="autosave.saveState.value"
      :conflict="autosave.conflictNotice.value"
      :readonly="autosave.readonly.value"
      :draft-title="draftTitle"
      @back="goCreationCenter"
      @save-draft="onSaveDraft"
      @retry="autosave.retry"
      @reload="autosave.reloadRemote"
    />

    <XhsStudioStepsBar
      :steps="XHS_STUDIO_STEPS"
      :current="steps.current.value"
      :reached="steps.reached.value"
      @select="steps.go"
    />

    <div v-if="toast" class="xhs-toast" role="status" aria-live="polite">{{ toast }}</div>

    <div class="xhs-layout">
      <aside class="xhs-side">
        <XhsSideConfigPanel
          :genre-options="tuning.genreOptions.value"
          :style-options="tuning.styleOptions.value"
          :formula-options="tuning.formulaOptions.value"
          v-model:genre="engine.genre.value"
          v-model:style="engine.style.value"
          v-model:title-formula="engine.titleFormula.value"
          v-model:audience="tuning.audience.value"
          :loading="engine.styleSkillsLoading.value"
          :error="engine.styleSkillsError.value"
          :balance="creditBalance"
          :balance-error="credits.error.value"
          :format-summary="format.formatRuleSummary.value"
          :emoji-hint="emojiHint"
          :can-generate="canGenerate"
          :generating="generating"
          :generate-label="generateLabel"
          @retry="tuning.retry"
          @generate="onGenerate"
          @request-login="emit('request-login')"
        />
      </aside>

      <section class="xhs-stage glass-card">
        <template v-if="steps.current.value === 'pick'">
          <XhsTopicPickerPanel @request-login="emit('request-login')" />
          <!-- F-01：找回旧视图两项既有能力——创作简报（表达身份/已确认经历/商业关系）与
               我的文风（VOICE_PLATFORMS 含 xiaohongshu）。extraInstructions 已由选题表单
               （切入角度+内容概要）承担且提交时会覆写，经 hideFields 收敛避免双入口互踩。 -->
          <CreationBriefEditor
            v-model="engine.brief.value"
            :disabled="autosave.readonly.value"
            :hide-fields="['extraInstructions']"
            :before-role-change="voicePanel?.confirmDiscard"
          />
          <VoiceProfilePanel
            ref="voicePanel"
            v-model="engine.brief.value"
            platform="xiaohongshu"
            genre="note"
            :draft-id="autosave.draftId.value"
            :disabled="autosave.readonly.value"
            :edited="engine.content.value"
          />
        </template>
        <template v-else-if="steps.current.value === 'generate'">
          <XhsTitleCandidates />
          <XhsBodyEditor @enter-proof="steps.go('proof')" />
          <XhsTopicTagsEditor />
          <XhsImageSeriesPanel @open-lightbox="openLightbox" />
          <XhsCompliancePanel mode="inline" />
        </template>
        <template v-else-if="steps.current.value === 'proof'">
          <XhsCompliancePanel mode="check" />
          <XhsProofChecklist @go-edit="() => steps.go('generate')" />
        </template>
        <XhsPublishPanel
          v-else-if="steps.current.value === 'publish'"
          @open-lightbox="openLightbox"
        />
      </section>

      <aside class="xhs-preview">
        <XhsPhonePreview @open="openLightbox" />
      </aside>
    </div>

    <ArticleLightbox :src="lightboxSrc" @close="closeLightbox" />
  </div>
</template>

<script setup lang="ts">
/**
 * 小红书专属创作台视图骨架（阶段 0）：三栏布局 + 四步显隐 + 步骤条 + 左栏最终态。
 * 纯装配（方案 §4.0/§4.1）：引擎/持久化/步骤机在 provideXhsStudioContext 内组合并
 * provide；中栏四步与右栏在阶段 0 放 EmptyState 占位，并行阶段各自替换组件标签。
 * 路由注册由集成工程师最后切换（方案 §2.2），本视图未接入前不可达。
 */
import { computed, onMounted, ref } from 'vue'
import { useCredits } from '../../composables/useCredits'
import { getPlatformFormatRule } from '../../config/platform-format-rules'
import ArticleLightbox from '../article/components/ArticleLightbox.vue'
import CreationBriefEditor from '../../components/CreationBriefEditor.vue'
import VoiceProfilePanel from '../../components/creation/VoiceProfilePanel.vue'
import { provideXhsStudioContext } from './composables/useXhsStudioContext'
import XhsStudioTopBar from './components/XhsStudioTopBar.vue'
import XhsStudioStepsBar from './components/XhsStudioStepsBar.vue'
import XhsSideConfigPanel from './components/XhsSideConfigPanel.vue'
import XhsTopicPickerPanel from './components/XhsTopicPickerPanel.vue'
import XhsTitleCandidates from './components/XhsTitleCandidates.vue'
import XhsBodyEditor from './components/XhsBodyEditor.vue'
import XhsTopicTagsEditor from './components/XhsTopicTagsEditor.vue'
import XhsPhonePreview from './components/XhsPhonePreview.vue'
import XhsImageSeriesPanel from './components/XhsImageSeriesPanel.vue'
import XhsCompliancePanel from './components/XhsCompliancePanel.vue'
import XhsProofChecklist from './components/XhsProofChecklist.vue'
import XhsPublishPanel from './components/XhsPublishPanel.vue'
import { XHS_STUDIO_STEPS } from './types'
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
} = provideXhsStudioContext({
  handoff: () => props.creationHandoff,
  goCreationCenter: () => emit('open-view', 'ai-center'),
})

/** 积分：真实余额（null=未加载；错误文案由 store error 区分），挂载时幂等拉取一次。 */
const credits = useCredits()
onMounted(() => { void credits.loadBalance() })
const creditBalance = computed(() => credits.balance.value === null ? null : credits.currentBalance.value)

/** 左栏主按钮：pick 步=以当前选题生成标题；generate 步=重新生成（换一批真实候选）。 */
const generating = computed(() => engine.titlesLoading.value || engine.contentLoading.value)
const canGenerate = computed(() => steps.current.value !== 'pick' || engine.topic.value.trim().length > 0)
const generateLabel = computed(() => steps.current.value === 'pick' ? '✦ AI 生成内容' : '⟳ 重新生成')

const draftTitle = computed(() => (engine.selectedTitle.value || engine.topic.value).trim().slice(0, 60))
const emojiHint = computed(() => getPlatformFormatRule('xiaohongshu')?.emojiHint ?? '')

async function onGenerate(): Promise<void> {
  await engine.fetchTitles()
  if (engine.titles.value.length > 0) steps.go('generate')
  else if (engine.error.value) notify(engine.error.value)
}

async function onSaveDraft(): Promise<void> {
  const saved = await autosave.flush()
  notify(saved ? '草稿已保存' : '保存失败，请稍后重试')
}

/** 图卡/预览点击放大（并行组件经 emit 上抛；骨架阶段先备好接入口）。 */
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
