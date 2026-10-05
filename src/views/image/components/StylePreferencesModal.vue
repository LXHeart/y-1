<template>
  <GlModal v-if="visible" title="旧版风格偏好（只读）" scroll @close="$emit('toggle')">
    <p>旧偏好不会自动归入任何身份。请在“我的文风”中选择身份、勾选条目并明确保存后导入。</p>
    <p v-if="loading" role="status">正在读取旧偏好…</p>
    <ul v-else-if="preferences.length"><li v-for="(rule, index) in preferences" :key="index">{{ rule }}</li></ul>
    <p v-else>暂无旧版风格偏好。</p>
  </GlModal>
</template>
<script setup lang="ts">
import GlModal from '../../../components/GlModal.vue'
defineProps<{
  visible: boolean
  loading: boolean
  preferences: string[]
  saving: boolean
  optimizing: boolean
  optimizedPreferences: string[] | null
  optimizeError: string
  page: number
  totalPages: number
  paginatedPreferences: string[]
  paginatedStartIndex: number
  editingIndex: number | null
  editingValue: string
}>()

defineEmits<{
  toggle: []
  optimize: []
  'confirm-optimize': []
  'cancel-optimize': []
  'delete': [index: number]
  'start-edit': [index: number]
  'confirm-edit': []
  'cancel-edit': []
  'update:page': [page: number]
  'update:editing-value': [value: string]
}>()
</script>
