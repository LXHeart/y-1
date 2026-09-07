<script setup lang="ts">
import { ref } from 'vue'
import AiPlatformCredentialsPanel from '../../../components/AiPlatformCredentialsPanel.vue'
import AiPlatformModelsPanel from '../../../components/AiPlatformModelsPanel.vue'
import AiPriceTablePanel from '../../../components/AiPriceTablePanel.vue'

defineOptions({ name: 'AdminAiModelsPanel' })

/**
 * AI 模型面板（任务书 #91 A4 自 AdminView.vue 内联分支整段迁出，纯搬运；无 fetch）。
 * 凭据/模型/价目三面板组合，changed 转发保持。
 */
const modelsPanel = ref<InstanceType<typeof AiPlatformModelsPanel> | null>(null)
</script>

<template>
  <!-- 凭据在上、模型在下：模型配置引用凭据，先有凭据才谈得上指向它。
       changed 转发：凭据增删改/停用后模型面板立即重拉凭据下拉，无需整页刷新。 -->
  <AiPlatformCredentialsPanel @changed="modelsPanel?.reloadCredentials()" />
  <AiPlatformModelsPanel ref="modelsPanel" />
  <AiPriceTablePanel />
</template>
