<script setup lang="ts">
import type { CreationVoiceSample } from '../../types/creation-voice'
const props = defineProps<{ modelValue: CreationVoiceSample[]; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: CreationVoiceSample[]] }>()
function update(index: number, patch: Partial<CreationVoiceSample>) {
  emit('update:modelValue', props.modelValue.map((sample, i) => i === index ? { ...sample, ...patch } : sample))
}
function add() {
  emit('update:modelValue', [...props.modelValue, { id: crypto.randomUUID(), platform: 'zhihu', genre: 'article', text: '', consent: false as true }])
}
</script>
<template>
  <fieldset class="gl-field" :disabled="disabled">
    <legend>本人范文（最多5份）</legend>
    <p>只学习表达，不借用范文中的人物、价格或经历。保存前请移除私密信息。</p>
    <div v-for="(sample, index) in modelValue" :key="sample.id" class="gl-field">
      <label>范文{{ index + 1 }}平台<select :value="sample.platform" @change="update(index, { platform: ($event.target as HTMLSelectElement).value as CreationVoiceSample['platform'] })"><option value="zhihu">知乎</option><option value="xiaohongshu">小红书</option><option value="dianping">大众点评</option><option value="moments">朋友圈</option></select></label>
      <label>范文{{ index + 1 }}体裁<select :value="sample.genre" @change="update(index, { genre: ($event.target as HTMLSelectElement).value as CreationVoiceSample['genre'] })"><option value="article">文章</option><option value="answer">回答</option><option value="note">笔记</option><option value="short-post">短文</option></select></label>
      <label>范文{{ index + 1 }}正文<textarea :value="sample.text" maxlength="2000" rows="4" @input="update(index, { text: ($event.target as HTMLTextAreaElement).value })" /></label>
      <label><input type="checkbox" :checked="sample.consent" @change="update(index, { consent: ($event.target as HTMLInputElement).checked as true })">这是本人作品或已获准使用</label>
      <button type="button" class="secondary-command" :aria-label="`删除范文${index + 1}`" @click="emit('update:modelValue', modelValue.filter((_, i) => i !== index))">删除范文</button>
    </div>
    <button type="button" class="secondary-command" :disabled="modelValue.length >= 5" @click="add">添加范文</button>
  </fieldset>
</template>
