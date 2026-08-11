<template>
  <article class="gl-card gl-card-wide">
    <h3>3. 发布任务<span v-if="revisingTask" class="gl-hint"> · 正在修订已发布任务（保存出新版本）</span><span v-else-if="editingDraft" class="gl-hint"> · 正在编辑草稿（保存后仍为草稿，需在上方「提交审核」）</span><span v-else class="gl-hint"> · 提交后经平台内容审核，通过后在大厅上架</span></h3>
    <div class="gl-row">
      <label>资源范围
        <select :value="selectedStoreId" :disabled="Boolean(editingDraft || revisingTask)" @change="$emit('change-store', ($event.target as HTMLSelectElement).value)">
          <option v-if="hasOrganizationAccess" value="">组织级任务</option>
          <option v-for="store in stores" :key="store.id" :value="store.id">门店：{{ store.name }}</option>
        </select>
      </label>
      <input :value="form.title" placeholder="任务标题" @input="updateField('title', ($event.target as HTMLInputElement).value)" />
      <input :value="form.platform" placeholder="平台（可选）" @input="updateField('platform', ($event.target as HTMLInputElement).value)" />
      <input :value="form.contentForm" placeholder="内容形式（可选）" @input="updateField('contentForm', ($event.target as HTMLInputElement).value)" />
    </div>
    <div class="gl-row">
      <input :value="form.description" placeholder="任务描述（可选）" @input="updateField('description', ($event.target as HTMLInputElement).value)" />
    </div>
    <div class="gl-row">
      <label>名额 <input :value="form.maxSlots" type="number" min="1" @input="updateField('maxSlots', Number(($event.target as HTMLInputElement).value))" /></label>
      <label>赏金 ¥<input :value="form.bountyYuan" type="number" min="0" :disabled="!canPublishBounty" @input="updateField('bountyYuan', Number(($event.target as HTMLInputElement).value))" /></label>
      <label>报名截止 <input :value="form.applicationDeadline" type="datetime-local" @input="updateField('applicationDeadline', ($event.target as HTMLInputElement).value)" /></label>
      <label>最低等级
        <select :value="form.minRecommenderLevel" @change="updateField('minRecommenderLevel', Number(($event.target as HTMLSelectElement).value))">
          <option v-for="level in 5" :key="level" :value="level">Lv{{ level }}</option>
        </select>
      </label>
    </div>
    <div class="gl-row">
      <button v-if="!revisingTask" type="button" :disabled="!activeOrgId || loading" @click="$emit('publish')">提交审核</button>
      <button type="button" :disabled="!activeOrgId || loading" @click="$emit('save-draft')">{{ revisingTask ? '保存修订' : (editingDraft ? '保存草稿' : '存为草稿') }}</button>
      <button v-if="editingDraft || revisingTask" type="button" :disabled="loading" @click="$emit('reset-form')">取消编辑</button>
    </div>
    <p class="gl-hint">赏金 &gt; 0 的任务为资金型：接受报名时会走资金预留 Saga（异步）。草稿不占发布额度、不需资金权限。已发布任务可「编辑」出新版本；改赏金/平台<b>只影响新报名</b>，已接受的履约按其接受时的金额结算（snapshot-pinning）。</p>
  </article>
</template>

<script setup lang="ts">
import type { Store } from '../../../types/grassland'

interface TaskFormData {
  title: string
  description: string
  platform: string
  contentForm: string
  maxSlots: number
  bountyYuan: number
  applicationDeadline: string
  minRecommenderLevel: number
}

defineProps<{
  form: TaskFormData
  editingDraft: { id: string; version: number } | null
  revisingTask: { id: string; version: number } | null
  stores: Store[]
  selectedStoreId: string
  activeOrgId: string
  hasOrganizationAccess: boolean
  canPublishBounty: boolean
  loading: boolean
}>()

const emit = defineEmits<{
  'update:field': [field: string, value: string | number]
  'change-store': [storeId: string]
  publish: []
  'save-draft': []
  'reset-form': []
}>()

function updateField(field: string, value: string | number): void {
  emit('update:field', field, value)
}
</script>
