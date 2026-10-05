<script setup lang="ts">
import { ref, watch } from 'vue'
const props = defineProps<{ candidates: string[]; busy: boolean; original?: string; edited?: string }>()
const emit = defineEmits<{ preview: [original: string, edited: string, reason: string]; confirm: [rules: string[]] }>()
const originalText = ref(props.original ?? ''), editedText = ref(props.edited ?? ''), reason = ref('style')
const selected = ref<number[]>([]), values = ref<string[]>([])
watch(() => props.candidates, value => { values.value = [...value]; selected.value = [] }, { immediate: true })
</script>
<template>
  <section class="gl-field">
    <h4>从改稿提取文风</h4>
    <p>仅保存你确认的长期表达习惯；提取不会自动写入档案。</p>
    <label>修改原因<select v-model="reason" :disabled="busy"><option value="style">长期表达习惯</option><option value="fact">事实纠错</option><option value="one-off">本篇一次性要求</option></select></label>
    <label>原文片段<textarea v-model="originalText" maxlength="12000" rows="3" :disabled="busy" /></label>
    <label>修改后片段<textarea v-model="editedText" maxlength="12000" rows="3" :disabled="busy" /></label>
    <button type="button" class="secondary-command" :disabled="busy" @click="emit('preview', originalText, editedText, reason)">{{ busy ? '提取中…' : '提取候选' }}</button>
    <fieldset v-if="values.length" :disabled="busy">
      <legend>选择长期规则（默认不选，可编辑）</legend>
      <label v-for="(_, index) in values" :key="index"><input v-model="selected" type="checkbox" :value="index" :aria-label="`选择候选${index + 1}`"><input v-model="values[index]" :aria-label="`候选${index + 1}`" maxlength="300"></label>
      <button type="button" class="secondary-command" :disabled="!selected.length || selected.some(i => !values[i]?.trim())" @click="emit('confirm', selected.map(i => values[i]!.trim()))">加入待保存规则</button>
    </fieldset>
  </section>
</template>
