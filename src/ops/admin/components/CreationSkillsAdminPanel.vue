<template>
  <div class="creation-skills-panel">
    <section aria-labelledby="creation-skills-title">
      <div class="panel-toolbar">
        <div>
          <h3 id="creation-skills-title">创作风格 skill 库</h3>
          <p>创作三选择器（标题套路/体裁/文风）的目录与注入 prompt——改动即时生效（生成时直读），停用即从用户端目录消失。「适用平台」留空 = 通用，指定则只在该平台的创作流出现。</p>
        </div>
        <button class="refresh-btn" type="button" :disabled="loading" data-test="creation-skills-refresh" @click="load">刷新</button>
      </div>

      <div class="filter-pills" role="tablist" aria-label="分类过滤">
        <button
          v-for="filter in FILTERS"
          :key="filter.key"
          type="button"
          :class="{ active: categoryFilter === filter.key }"
          :data-test="`creation-skills-filter-${filter.key}`"
          @click="categoryFilter = filter.key"
        >{{ filter.label }}</button>
      </div>

      <p v-if="error" class="error-msg" role="alert">{{ error }}</p>
      <div v-if="loading" class="loading-state">加载中...</div>

      <div v-else-if="filtered.length" class="table-card">
        <div class="table-scroll">
          <table class="skills-table">
            <thead>
              <tr>
                <th>分类</th><th>名称</th><th>code</th><th>描述</th>
                <th>适用平台</th><th>启用</th><th>更新时间</th><th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="skill in filtered" :key="skill.id">
                <td><span class="type-tag">{{ categoryLabel(skill.category) }}</span></td>
                <td class="td-name">{{ skill.name }}</td>
                <td><code>{{ skill.code }}</code></td>
                <td class="td-desc">{{ skill.description || '-' }}</td>
                <td class="td-platforms" :data-test="`creation-skills-platforms-${skill.code}`">
                  {{ platformsLabel(skill.applicablePlatforms) }}
                </td>
                <td>
                  <label class="switch-toggle">
                    <input
                      type="checkbox"
                      role="switch"
                      :data-test="`creation-skills-toggle-${skill.code}`"
                      :checked="skill.enabled"
                      :disabled="savingId === skill.id"
                      @change="onToggle(skill)"
                    >
                    <span>{{ skill.enabled ? '已启用' : '已停用' }}</span>
                  </label>
                </td>
                <td class="td-time">{{ formatDateTime(skill.updatedAt) }}</td>
                <td>
                  <button
                    class="edit-btn"
                    type="button"
                    :data-test="`creation-skills-edit-${skill.code}`"
                    @click="openEdit(skill)"
                  >编辑</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <p v-else class="td-empty">该分类暂无风格条目</p>
      <p class="panel-note">共 {{ filtered.length }} 条 / 全量 {{ skills.length }} 条——固定量配置不分页；新增/删除/调序不在本面板（决策 G）。</p>
    </section>

    <Teleport to="body">
      <div v-if="editing" class="modal-overlay" data-test="creation-skills-modal" @click.self="closeEdit">
        <div class="modal-card">
          <header class="modal-header">
            <h4 class="modal-title">编辑：{{ editing.name }}（{{ categoryLabel(editing.category) }}）</h4>
            <button class="modal-close" type="button" aria-label="关闭" @click="closeEdit">×</button>
          </header>
          <form class="modal-body" @submit.prevent="submitEdit">
            <label class="field-label">描述（用户端 chips 下方提示行）
              <input v-model="draft.description" class="field-input" type="text" maxlength="200" data-test="creation-skills-modal-desc">
            </label>
            <label class="field-label">注入 prompt（生成时追加进 system 消息）
              <textarea
                v-model="draft.promptContent"
                class="field-input field-textarea"
                rows="10"
                maxlength="2000"
                data-test="creation-skills-modal-prompt"
              ></textarea>
            </label>
            <fieldset class="platform-fieldset">
              <legend class="field-label">适用平台（全不选 = 通用，所有平台可见）</legend>
              <label
                v-for="option in PLATFORM_OPTIONS"
                :key="option.id"
                class="platform-option"
              >
                <input
                  type="checkbox"
                  :value="option.id"
                  :checked="draft.applicablePlatforms.includes(option.id)"
                  :data-test="`creation-skills-modal-platform-${option.id}`"
                  @change="togglePlatform(option.id)"
                >
                <span>{{ option.label }}</span>
              </label>
              <p class="platform-hint" data-test="creation-skills-modal-platform-hint">
                当前：{{ platformsLabel(draft.applicablePlatforms) }}
              </p>
            </fieldset>
            <label class="switch-toggle modal-switch">
              <input v-model="draft.enabled" type="checkbox" role="switch" data-test="creation-skills-modal-enabled">
              <span>{{ draft.enabled ? '启用（用户端可见可选）' : '停用（目录消失，已选生成会明确报错）' }}</span>
            </label>
            <p v-if="editError" class="error-msg" role="alert" data-test="creation-skills-modal-error">{{ editError }}</p>
            <div class="modal-actions">
              <button class="btn-cancel" type="button" @click="closeEdit">取消</button>
              <button class="btn-confirm" type="submit" :disabled="saving" data-test="creation-skills-modal-save">
                {{ saving ? '保存中…' : '保存' }}
              </button>
            </div>
          </form>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { request } from '../../../composables/grassland-http'
import { GrasslandHttpError } from '../../../composables/grassland-http'

/**
 * 创作风格 skill 治理台面板（任务书 #57 决策 G）：仅 查看/编辑（描述+prompt+启停），
 * 乐观锁 version 冲突 409；不分页（≤22 行固定量配置）。
 * prompt 是生成流注入原文——编辑即热更（后端直读无缓存），红线：不得删掉种子里的安全底线句。
 */

type Category = 'TITLE_FORMULA' | 'GENRE' | 'STYLE'

interface AdminSkill {
  id: string
  category: Category
  code: string
  name: string
  description: string
  promptContent: string
  enabled: boolean
  sortOrder: number
  version: number
  updatedAt: string | null
  /** 任务书 #62 P3：适用平台归属；空数组 = 全平台通用。 */
  applicablePlatforms: string[]
}

const FILTERS = [
  { key: 'ALL' as const, label: '全部' },
  { key: 'TITLE_FORMULA' as const, label: '标题套路' },
  { key: 'GENRE' as const, label: '内容体裁' },
  { key: 'STYLE' as const, label: '文风口吻' },
]

/**
 * 任务书 #62 P3：可归属平台。值域与用户端 `skillPlatformId` 口径一致（canonical id），
 * 抖音单列——它与小红书共用 platform 值但创作流表现不同（#57 isDouyinMode）。
 */
const PLATFORM_OPTIONS = [
  { id: 'xiaohongshu', label: '小红书' },
  { id: 'zhihu', label: '知乎' },
  { id: 'douyin', label: '抖音' },
  { id: 'wechat', label: '公众号' },
]

const CATEGORY_LABELS: Record<Category, string> = {
  TITLE_FORMULA: '标题套路',
  GENRE: '内容体裁',
  STYLE: '文风口吻',
}

const skills = ref<AdminSkill[]>([])
const loading = ref(false)
const error = ref('')
const categoryFilter = ref<(typeof FILTERS)[number]['key']>('ALL')
const savingId = ref('')

const editing = ref<AdminSkill | null>(null)
const draft = ref({ description: '', promptContent: '', enabled: true, applicablePlatforms: [] as string[] })
const editError = ref('')
const saving = ref(false)

const filtered = computed(() => categoryFilter.value === 'ALL'
  ? skills.value
  : skills.value.filter((skill) => skill.category === categoryFilter.value))

function categoryLabel(category: Category): string {
  return CATEGORY_LABELS[category] || category
}

/** 空数组 = 通用；未知 id 原样显示（存量数据不擅自吞掉）。 */
function platformsLabel(platforms: string[] | undefined): string {
  if (!platforms || platforms.length === 0) return '通用'
  return platforms
    .map((id) => PLATFORM_OPTIONS.find((option) => option.id === id)?.label || id)
    .join(' / ')
}

/** 多选勾选：不可变更新（就地 push 不触发 ref 依赖，勾选看不出变化）。 */
function togglePlatform(id: string): void {
  const current = draft.value.applicablePlatforms
  draft.value = {
    ...draft.value,
    applicablePlatforms: current.includes(id)
      ? current.filter((item) => item !== id)
      : [...current, id],
  }
}

function formatDateTime(iso: string | null): string {
  if (!iso) return '-'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN')
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const data = await request<{ skills: AdminSkill[] }>('/api/admin/creation-style-skills')
    skills.value = data?.skills || []
  } catch (err: unknown) {
    error.value = err instanceof Error ? err.message : '风格库加载失败'
  } finally {
    loading.value = false
  }
}

/**
 * 整行 PUT（后端全必填契约）：name/sortOrder 不在编辑面，取行现值。
 *
 * 任务书 #62 P3：`applicablePlatforms` **总是显式发**——后端把「省略键」当作保持原归属，
 * 靠省略无法把归属改回通用（空数组才行），而清空归属正是治理台的常规操作。
 */
async function putSkill(
  skill: AdminSkill,
  overrides: Partial<Pick<AdminSkill, 'description' | 'promptContent' | 'enabled' | 'applicablePlatforms'>>,
): Promise<AdminSkill | null> {
  const updated = await request<{ skill: AdminSkill }>(`/api/admin/creation-style-skills/${skill.id}`, {
    method: 'PUT',
    body: JSON.stringify({
      name: skill.name,
      description: overrides.description ?? skill.description,
      promptContent: overrides.promptContent ?? skill.promptContent,
      enabled: overrides.enabled ?? skill.enabled,
      applicablePlatforms: overrides.applicablePlatforms ?? skill.applicablePlatforms ?? [],
      expectedVersion: skill.version,
    }),
  })
  return updated?.skill || null
}

/** 启用开关 change 即 PUT（照 AiProviderKeysPanel 口径）；409 → 提示刷新。 */
async function onToggle(skill: AdminSkill): Promise<void> {
  savingId.value = skill.id
  error.value = ''
  try {
    const next = await putSkill(skill, { enabled: !skill.enabled })
    if (next) skills.value = skills.value.map((row) => (row.id === next.id ? next : row))
  } catch (err: unknown) {
    error.value = conflictMessage(err, '启停失败')
  } finally {
    savingId.value = ''
  }
}

function openEdit(skill: AdminSkill): void {
  editing.value = skill
  draft.value = {
    description: skill.description,
    promptContent: skill.promptContent,
    enabled: skill.enabled,
    applicablePlatforms: [...(skill.applicablePlatforms ?? [])],
  }
  editError.value = ''
}

function closeEdit(): void {
  editing.value = null
  editError.value = ''
}

async function submitEdit(): Promise<void> {
  const target = editing.value
  if (!target || saving.value) return
  saving.value = true
  editError.value = ''
  try {
    const next = await putSkill(target, { ...draft.value })
    if (next) {
      skills.value = skills.value.map((row) => (row.id === next.id ? next : row))
      editing.value = null
    }
  } catch (err: unknown) {
    editError.value = conflictMessage(err, '保存失败')
  } finally {
    saving.value = false
  }
}

/** 409 统一口径（任务书 #57 S3）：提示刷新后重试。 */
function conflictMessage(err: unknown, fallback: string): string {
  if (err instanceof GrasslandHttpError && err.status === 409) {
    return '已被他人修改，请刷新后重试'
  }
  return err instanceof Error ? err.message : fallback
}

onMounted(() => { void load() })
</script>

<style scoped>
.creation-skills-panel > section { display: grid; gap: 16px; }
.panel-toolbar { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.panel-toolbar h3, .panel-toolbar p { margin: 0; }
.panel-toolbar h3 { font-size: 1rem; }
.panel-toolbar p { margin-top: 4px; color: var(--color-text-muted); font-size: 0.82rem; }
.refresh-btn { min-height: 32px; padding: 0 12px; border-radius: var(--radius-sm); border: 1px solid var(--color-border); background: transparent; color: var(--color-text-secondary); font-size: 0.78rem; cursor: pointer; }
.refresh-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.filter-pills { display: inline-flex; flex-wrap: wrap; gap: 4px; padding: 4px; border-radius: var(--radius-pill); border: 1px solid var(--color-border); background: var(--surface-muted); justify-self: start; }
.filter-pills button { min-height: 30px; padding: 0 14px; border: 1px solid transparent; border-radius: var(--radius-pill); background: transparent; color: var(--color-text-secondary); font: inherit; font-size: 0.8rem; font-weight: 600; cursor: pointer; }
.filter-pills button.active { background: var(--color-surface); border-color: var(--color-border); color: var(--color-text); }

.table-card { border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); }
.table-scroll { overflow-x: auto; }
.skills-table { width: 100%; border-collapse: collapse; font-size: 0.84rem; }
.skills-table th { text-align: left; padding: 10px 12px; color: var(--color-text-muted); font-size: 0.76rem; font-weight: 600; border-bottom: 1px solid var(--color-border); white-space: nowrap; }
.skills-table td { padding: 10px 12px; border-bottom: 1px solid var(--color-border); vertical-align: top; }
.skills-table tr:last-child td { border-bottom: none; }
.skills-table code { font-family: var(--font-mono); font-size: 0.76rem; color: var(--color-text-secondary); }
.td-name { font-weight: 600; white-space: nowrap; }
.td-desc { color: var(--color-text-muted); max-width: 320px; }
/* 任务书 #62 P3：适用平台列与归属多选（值域见 PLATFORM_OPTIONS）。 */
.td-platforms { color: var(--color-text-secondary); font-size: 0.8rem; white-space: nowrap; }
.platform-fieldset { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin: 0; padding: 0; border: 0; }
.platform-fieldset legend { padding: 0; }
.platform-option { display: inline-flex; align-items: center; gap: 6px; font-size: 0.8rem; color: var(--color-text-secondary); cursor: pointer; }
.platform-option input { accent-color: var(--color-accent); width: 16px; height: 16px; }
.platform-hint { flex: 1 1 100%; margin: 0; color: var(--color-text-muted); font-size: 0.78rem; }
.td-time { white-space: nowrap; color: var(--color-text-muted); font-size: 0.78rem; }
.td-empty { text-align: center; padding: var(--space-xl); color: var(--color-text-muted); margin: 0; }
.panel-note { margin: 0; color: var(--color-text-muted); font-size: 0.78rem; }

.type-tag { display: inline-block; padding: 3px 7px; border: 1px solid var(--color-border); border-radius: var(--radius-pill); background: var(--surface-muted); white-space: nowrap; font-size: 0.76rem; }

.switch-toggle { display: inline-flex; align-items: center; gap: 6px; font-size: 0.78rem; color: var(--color-text-secondary); cursor: pointer; }
.switch-toggle input { accent-color: var(--color-accent); width: 16px; height: 16px; }
.modal-switch { justify-content: start; }

.edit-btn { min-height: 30px; padding: 0 12px; border-radius: var(--radius-sm); border: 1px solid var(--color-border); background: transparent; color: var(--color-text-secondary); font-size: 0.78rem; cursor: pointer; }
.edit-btn:hover { border-color: var(--color-border-hover); background: var(--color-surface-hover); }

.loading-state { padding: var(--space-xl); text-align: center; color: var(--color-text-muted); font-size: 0.9rem; }
.error-msg { padding: var(--space-sm) var(--space-md); border-radius: var(--radius-sm); background: color-mix(in srgb, var(--color-danger) 10%, transparent); border: 1px solid color-mix(in srgb, var(--color-danger) 20%, transparent); color: var(--color-danger); font-size: 0.86rem; margin: 0; }

@media (max-width: 720px) {
  .td-desc { max-width: 200px; }
}
</style>
