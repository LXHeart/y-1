<template>
  <section class="ai-control-panel" aria-labelledby="bgm-library-title">
    <header class="panel-heading">
      <div>
        <h3 id="bgm-library-title">BGM 曲库</h3>
        <p>CC0 / 免版税曲目，按情绪分类供成片合成选曲；种子为空，来源与许可要求见《BGM 曲库入库指引》</p>
      </div>
      <button type="button" class="primary-command" data-action="open-upload" @click="openUpload">
        上传曲目
      </button>
    </header>

    <p v-if="error" class="error-state" role="alert">{{ error }}</p>
    <p v-if="loading" class="empty-state">正在加载曲库...</p>
    <p v-else-if="!error && items.length === 0" class="empty-state">曲库为空，请先上传曲目</p>
    <div v-else class="table-wrap">
      <table class="data-table">
        <thead>
          <tr>
            <th>曲名</th><th>情绪标签</th><th>大小</th><th>时长</th><th>状态</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.id">
            <td><strong>{{ item.name }}</strong></td>
            <td>{{ moodLabels(item.moodTags) }}</td>
            <td>{{ formatSize(item.sizeBytes) }}</td>
            <td>{{ formatDuration(item.durationMs) }}</td>
            <td>
              <span class="status-tag" :class="item.enabled ? 'status-active' : 'status-retired'">
                {{ item.enabled ? '启用' : '停用' }}
              </span>
            </td>
            <td class="row-actions">
              <button type="button" data-action="preview" @click="preview(item)">试听</button>
              <button type="button" data-action="toggle-enabled" @click="toggleEnabled(item)">
                {{ item.enabled ? '停用' : '启用' }}
              </button>
              <button type="button" class="danger-command" data-action="delete" @click="remove(item)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <OpsPagination
      v-if="total > pageSize"
      :limit="pageSize"
      :offset="(page - 1) * pageSize"
      :total="total"
      @change="turnOffset"
    />

    <GlModal v-if="uploadOpen" title="上传 BGM 曲目" @close="closeUpload">
      <form id="bgm-upload-form" @submit.prevent="submitUpload">
        <p class="form-hint">仅支持 mp3 / m4a，单文件不超过 10MB；入库前请确认曲目许可为 CC0 或免版税。</p>
        <label>音频文件<input ref="fileInputRef" name="file" type="file" accept=".mp3,.m4a,audio/mpeg,audio/mp4" required /></label>
        <label>曲名<input v-model.trim="uploadName" name="name" maxlength="100" required placeholder="如：清晨轻快旋律" /></label>
        <fieldset>
          <legend>情绪标签（至少一个）</legend>
          <div class="mood-options">
            <label v-for="mood in MOODS" :key="mood" class="mood-option">
              <input v-model="uploadMoods" type="checkbox" :value="mood" name="moods" />
              {{ mood }}
            </label>
          </div>
        </fieldset>
        <p v-if="uploadError" class="error-state compact" role="alert">{{ uploadError }}</p>
      </form>
      <template #actions>
        <button type="button" class="secondary-command" @click="closeUpload">取消</button>
        <button type="submit" form="bgm-upload-form" class="primary-command" :disabled="uploadSubmitting">
          {{ uploadSubmitting ? '上传中...' : '上传' }}
        </button>
      </template>
    </GlModal>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchApi } from '../composables/grassland-http'
import GlModal from './GlModal.vue'
import OpsPagination from '../ops/admin/components/OpsPagination.vue'

interface BgmTrackRow {
  id: string
  name: string
  moodTags: string
  contentType: string
  sizeBytes: number
  durationMs: number | null
  enabled: boolean
}

/** 与后端 BgmTrack.MOODS 同值集（P3 八标签）。 */
const MOODS = ['轻快', '温暖', '治愈', '燃', '悬念', '舒缓', '国风', '电子']

const items = ref<BgmTrackRow[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const loading = ref(false)
const error = ref('')

const uploadOpen = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)
const uploadName = ref('')
const uploadMoods = ref<string[]>([])
const uploadError = ref('')
const uploadSubmitting = ref(false)

onMounted(() => { void load() })

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const response = await fetchApi(`/api/admin/bgm-tracks?page=${page.value}&pageSize=${pageSize}`)
    const body = await response.json() as { data?: { items?: BgmTrackRow[]; total?: number } }
    if (!response.ok) throw new Error('曲库加载失败')
    items.value = body.data?.items ?? []
    total.value = body.data?.total ?? 0
  } catch (caught: unknown) {
    items.value = []
    error.value = caught instanceof Error ? caught.message : '曲库加载失败'
  } finally {
    loading.value = false
  }
}

/** OpsPagination 契约：父持 offset/limit 真源，组件只发 change(offset)。 */
function turnOffset(offset: number): void {
  page.value = Math.floor(offset / pageSize) + 1
  void load()
}

function openUpload(): void {
  uploadName.value = ''
  uploadMoods.value = []
  uploadError.value = ''
  uploadOpen.value = true
}

function closeUpload(): void {
  uploadOpen.value = false
  uploadError.value = ''
}

async function submitUpload(): Promise<void> {
  if (uploadMoods.value.length === 0) {
    uploadError.value = '至少选择一个情绪标签'
    return
  }
  const file = fileInputRef.value?.files?.[0]
  if (!file) {
    uploadError.value = '请选择音频文件'
    return
  }
  uploadSubmitting.value = true
  uploadError.value = ''
  try {
    const form = new FormData()
    form.append('file', file)
    form.append('name', uploadName.value)
    uploadMoods.value.forEach((mood) => form.append('moods', mood))
    const response = await fetchApi('/api/admin/bgm-tracks', { method: 'POST', body: form })
    if (!response.ok) {
      const body = await response.json() as { error?: string }
      throw new Error(body.error || '上传失败')
    }
    closeUpload()
    page.value = 1
    await load()
  } catch (caught: unknown) {
    uploadError.value = caught instanceof Error ? caught.message : '上传失败'
  } finally {
    uploadSubmitting.value = false
  }
}

async function toggleEnabled(item: BgmTrackRow): Promise<void> {
  error.value = ''
  try {
    const response = await fetchApi(`/api/admin/bgm-tracks/${item.id}`, {
      method: 'PUT',
      body: JSON.stringify({ enabled: !item.enabled }),
    })
    if (!response.ok) {
      const body = await response.json() as { error?: string }
      throw new Error(body.error || '操作失败')
    }
    await load()
  } catch (caught: unknown) {
    error.value = caught instanceof Error ? caught.message : '操作失败'
  }
}

async function preview(item: BgmTrackRow): Promise<void> {
  error.value = ''
  try {
    const response = await fetchApi(`/api/admin/bgm-tracks/${item.id}/preview-url`)
    const body = await response.json() as { data?: { previewUrl?: string } }
    const url = body.data?.previewUrl
    if (!response.ok || !url) throw new Error('试听地址获取失败')
    window.open(url, '_blank', 'noopener')
  } catch (caught: unknown) {
    error.value = caught instanceof Error ? caught.message : '试听地址获取失败'
  }
}

async function remove(item: BgmTrackRow): Promise<void> {
  const referencedHint = item.enabled ? '' : '（该曲目已被成片引用，删除将改为停用）'
  if (!window.confirm(`确认删除「${item.name}」？${referencedHint}`)) return
  error.value = ''
  try {
    const response = await fetchApi(`/api/admin/bgm-tracks/${item.id}`, { method: 'DELETE' })
    const body = await response.json() as { data?: { deleted?: boolean; disabled?: boolean } }
    if (!response.ok) throw new Error('删除失败')
    await load()
    if (body.data?.disabled) {
      error.value = `「${item.name}」已被成片引用，已改为停用`
    }
  } catch (caught: unknown) {
    error.value = caught instanceof Error ? caught.message : '删除失败'
  }
}

function moodLabels(raw: string): string {
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.join(' / ') : raw
  } catch {
    return raw
  }
}

function formatSize(bytes: number): string {
  if (!bytes) return '—'
  return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.round(bytes / 1024)} KB`
}

function formatDuration(durationMs: number | null): string {
  if (!durationMs) return '—'
  const seconds = Math.round(durationMs / 1000)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}
</script>
