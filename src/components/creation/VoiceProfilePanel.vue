<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { CreationBrief } from '../../types/creation'
import type { CreationVoiceGenre } from '../../types/creation-voice'
import GlModal from '../GlModal.vue'
import EmptyState from '../shared/EmptyState.vue'
import VoiceSamplesEditor from './VoiceSamplesEditor.vue'
import VoiceLearningPanel from './VoiceLearningPanel.vue'
import { useCreationVoice, VOICE_ROLES } from '../../composables/useCreationVoice'
const props = defineProps<{ modelValue: CreationBrief | null; platform: string; genre: CreationVoiceGenre; draftId?: string | null; disabled?: boolean; original?: string; edited?: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: CreationBrief] }>()
const brief = computed({ get: () => props.modelValue, set: value => { if (value) emit('update:modelValue', value) } })
const voice = useCreationVoice(brief, () => props.platform, () => props.draftId)
const { role, supported, profile, rules, samples, enabled, loading, saving, extracting, error, message, candidates, legacy, editing, stale, canUse } = voice
const legacySelected = ref<string[]>([])
watch(legacy, () => { legacySelected.value = [] }, { flush: 'sync' })
const title = computed(() => `我的文风 · ${role.value ? VOICE_ROLES[role.value] : '先选择表达身份'}`)
const rulesText = computed({ get: () => rules.value.join('\n'), set: value => { rules.value = value.trim() ? value.split('\n') : [] } })
function openEditor(event: MouseEvent) {
  // WebKit 的鼠标点击不会自动聚焦按钮，显式聚焦才能正确恢复弹窗触发点。
  const button = event.currentTarget as HTMLButtonElement
  button.focus()
  voice.edit()
}
defineExpose({ confirmDiscard: voice.confirmDiscard, open: voice.edit })
</script>
<template>
  <section v-if="supported" class="gl-zone studio-panel" data-testid="voice-profile-panel">
    <h3>{{ title }}</h3>
    <p v-if="!role">先选择表达身份</p>
    <template v-else>
      <label><input type="checkbox" :checked="modelValue?.voice?.mode === 'profile'" :disabled="disabled || (!canUse && modelValue?.voice?.mode !== 'profile')" @change="voice.choose(($event.target as HTMLInputElement).checked)">本次使用我的文风</label>
      <p v-if="loading" role="status">正在读取文风…</p>
      <p v-if="stale" role="alert">所选文风版本已失效，请读取最新版本后核对并重新开启。</p>
      <EmptyState v-if="profile?.revision === 0" title="还没有保存这类文风" description="可手动添加规则或从改稿提取。" />
      <button type="button" class="secondary-command" :disabled="disabled || loading || !profile" @click="openEditor">编辑我的文风</button>
      <button type="button" class="secondary-command" :disabled="loading || saving" @click="voice.load">读取最新版本</button>
    </template>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="message" role="status">{{ message }}</p>
    <GlModal v-if="editing" :title="title" wide scroll :persistent="saving || extracting" @close="voice.confirmDiscard">
      <fieldset class="gl-field voice-editor" :disabled="saving || disabled">
        <label><input v-model="enabled" type="checkbox">启用此身份档案</label>
        <label>长期表达规则（每行一条，最多30条）<textarea v-model="rulesText" rows="6" :aria-describedby="error ? 'voice-rules-help voice-edit-error' : 'voice-rules-help'" /></label>
        <p id="voice-rules-help">每条最多300字。可删除或替换旧规则，保存会更新完整档案。</p>
        <VoiceSamplesEditor v-model="samples" :disabled="saving" />
        <VoiceLearningPanel :candidates="candidates" :busy="extracting" :original="original" :edited="edited" @preview="(a, b, reason) => voice.preview(a, b, reason, genre)" @confirm="voice.addRules" />
        <button type="button" class="secondary-command" @click="legacySelected = []; voice.loadLegacy()">查看旧版风格偏好并导入</button>
        <fieldset v-if="legacy.length"><legend>选择导入规则（不修改旧数据）</legend><label v-for="(rule, index) in legacy" :key="index"><input v-model="legacySelected" type="checkbox" :value="rule">{{ rule }}</label><button type="button" class="secondary-command" :disabled="!legacySelected.length || new Set([...rules, ...legacySelected]).size > 30" @click="voice.addRules(legacySelected)">加入待保存规则</button></fieldset>
      </fieldset>
      <p v-if="error" id="voice-edit-error" role="alert">{{ error }}</p><p v-if="message" role="status">{{ message }}</p>
      <template #actions>
        <button type="button" class="gl-btn-primary" :disabled="saving || extracting || disabled" @click="voice.save">{{ saving ? '保存中…' : '保存文风' }}</button>
        <button type="button" class="secondary-command" :disabled="saving || extracting" @click="voice.confirmDiscard">取消</button>
        <button type="button" class="secondary-command" :disabled="saving || extracting" @click="voice.clear">清空当前身份文风</button>
        <button v-if="error" type="button" class="secondary-command" :disabled="loading || saving" @click="voice.load">读取最新版本后核对</button>
      </template>
    </GlModal>
  </section>
</template>
