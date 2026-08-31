<template>
  <div class="humanize-skills-panel">
    <section aria-labelledby="humanize-skills-title">
      <div class="panel-toolbar">
        <div>
          <h3 id="humanize-skills-title">去AI味规则库</h3>
          <p>
            激活后，所有创作型 AI 文字生成（文章/朋友圈/短剧/视频脚本等 12 场景）将自动注入所选规则；修改即刻生效。
            分析型流程（视频分析、内容安全、履约核验等）不注入。
          </p>
        </div>
        <button
          class="refresh-btn"
          type="button"
          :disabled="loading"
          data-test="humanize-skills-refresh"
          @click="load"
        >刷新</button>
      </div>

      <p v-if="error" class="error-msg" role="alert">{{ error }}</p>
      <div v-if="loading" class="loading-state">加载中...</div>

      <template v-else-if="skills.length">
        <fieldset class="activate-card">
          <legend>当前激活（平台级单选）</legend>
          <label class="activate-option">
            <input
              type="radio"
              name="humanize-active"
              value=""
              data-test="humanize-activate-off"
              :checked="activeSkillCode === ''"
              :disabled="activating"
              @change="onActivate('')"
            >
            <span class="activate-label">不注入</span>
            <span class="activate-hint">保持各场景原有 prompt，不追加任何文风约束</span>
          </label>
          <label v-for="skill in skills" :key="`activate-${skill.id}`" class="activate-option">
            <input
              type="radio"
              name="humanize-active"
              :value="skill.code"
              :data-test="`humanize-activate-${skill.code}`"
              :checked="activeSkillCode === skill.code"
              :disabled="activating || !skill.enabled"
              @change="onActivate(skill.code)"
            >
            <span class="activate-label">{{ skill.displayName }}</span>
            <span class="activate-hint">{{ skill.enabled ? skill.description : '已停用——启用后才能激活' }}</span>
          </label>
        </fieldset>

        <div class="table-card">
          <div class="table-scroll">
            <table class="skills-table">
              <thead>
                <tr>
                  <th>名称</th><th>code</th><th>说明</th><th>来源</th>
                  <th>启用</th><th>更新时间</th><th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="skill in skills" :key="skill.id" :data-test="`humanize-skill-row-${skill.code}`">
                  <td class="td-name">{{ skill.displayName }}</td>
                  <td><code>{{ skill.code }}</code></td>
                  <td class="td-desc">{{ skill.description || '-' }}</td>
                  <td class="td-source">
                    <a
                      v-if="skill.sourceRepo"
                      class="source-link"
                      :href="skill.sourceRepo"
                      target="_blank"
                      rel="noopener noreferrer"
                    >{{ shortRepo(skill.sourceRepo) }}</a>
                    <span v-else>-</span>
                    <span v-if="skill.sourceLicense" class="license-badge">{{ skill.sourceLicense }}</span>
                  </td>
                  <td>
                    <label class="switch-toggle">
                      <input
                        type="checkbox"
                        role="switch"
                        :data-test="`humanize-skill-toggle-${skill.code}`"
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
                      :data-test="`humanize-skill-edit-${skill.code}`"
                      @click="openEdit(skill)"
                    >编辑内容</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
        <p class="panel-note">
          共 {{ skills.length }} 条固定规则（MIT 来源蒸馏入库快照）——不新增/不删除行，仅编辑内容、启停与单选激活。
        </p>
      </template>
      <p v-else class="td-empty">暂无去AI味规则</p>
    </section>

    <GlModal v-if="editing" title="编辑去AI味规则" persistent scroll @close="closeEdit">
      <form id="humanize-skill-form" class="modal-form" @submit.prevent="submitEdit">
        <p class="modal-subject">{{ editing.displayName }}（<code>{{ editing.code }}</code>）</p>
        <label class="field-label">
          说明（治理台内部备注）
          <input
            v-model.trim="draft.description"
            type="text"
            maxlength="200"
            data-test="humanize-skill-modal-description"
          >
        </label>
        <label class="field-label">
          规则内容（注入 system prompt 的原文）
          <textarea
            v-model="draft.promptContent"
            rows="14"
            maxlength="3000"
            required
            data-test="humanize-skill-modal-prompt"
          ></textarea>
        </label>
        <p class="char-count">{{ draft.promptContent.length }} / 3000</p>
        <label class="switch-toggle">
          <input v-model="draft.enabled" type="checkbox" data-test="humanize-skill-modal-enabled">
          <span>{{ draft.enabled ? '已启用' : '已停用' }}</span>
        </label>
        <p class="modal-warning">
          规则只约束语言风格——注入模板已声明「不得改变事实、数字、专有名词与输出结构」，编辑时请勿删除该类底线表述。
        </p>
        <p v-if="editError" class="error-msg" role="alert" data-test="humanize-skill-modal-error">{{ editError }}</p>
      </form>
      <template #actions>
        <button type="button" class="btn-secondary" :disabled="saving" @click="closeEdit">取消</button>
        <button
          type="button"
          class="btn-primary"
          :disabled="saving"
          data-test="humanize-skill-modal-save"
          @click="submitEdit"
        >{{ saving ? '保存中...' : '保存' }}</button>
      </template>
    </GlModal>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import GlModal from '../../../components/GlModal.vue'
import { GrasslandHttpError, request } from '../../../composables/grassland-http'

/**
 * 去AI味 skill 治理台面板（任务书 #61）：3 条固定规则的内容编辑、启停与平台级单选激活。
 * 激活项存单行配置（`activeSkillCode` 空串=不注入），生成时后端直读无缓存——改完下一次生成即生效。
 * 乐观锁：行编辑用 `expectedVersion`、激活用 `expectedConfigVersion`，冲突统一 409 提示刷新。
 */

interface AdminSkill {
  id: string
  code: string
  displayName: string
  description: string
  promptContent: string
  sourceRepo: string
  sourceLicense: string
  enabled: boolean
  version: number
  updatedAt: string | null
}

interface ListResponse {
  skills: AdminSkill[]
  activeSkillCode: string
  configVersion: number
}

const skills = ref<AdminSkill[]>([])
const activeSkillCode = ref('')
const configVersion = ref(0)
const loading = ref(false)
const error = ref('')
const savingId = ref('')
const activating = ref(false)

const editing = ref<AdminSkill | null>(null)
const draft = ref({ description: '', promptContent: '', enabled: true })
const editError = ref('')
const saving = ref(false)

function shortRepo(url: string): string {
  return url.replace(/^https?:\/\/(www\.)?/, '').replace(/\/$/, '')
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
    const data = await request<ListResponse>('/api/admin/humanize-skills')
    skills.value = data?.skills || []
    activeSkillCode.value = data?.activeSkillCode || ''
    configVersion.value = data?.configVersion ?? 0
  } catch (err: unknown) {
    error.value = err instanceof Error ? err.message : '去AI味规则库加载失败'
  } finally {
    loading.value = false
  }
}

/** 整行 PUT（后端全必填契约）：displayName 不在编辑面，取行现值。 */
async function putSkill(
  skill: AdminSkill,
  overrides: Partial<Pick<AdminSkill, 'description' | 'promptContent' | 'enabled'>>,
): Promise<AdminSkill | null> {
  const updated = await request<{ skill: AdminSkill }>(`/api/admin/humanize-skills/${skill.id}`, {
    method: 'PUT',
    body: JSON.stringify({
      displayName: skill.displayName,
      description: overrides.description ?? skill.description,
      promptContent: overrides.promptContent ?? skill.promptContent,
      enabled: overrides.enabled ?? skill.enabled,
      expectedVersion: skill.version,
    }),
  })
  return updated?.skill || null
}

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

/** 激活单选 change 即 PUT；空串 → null（关闭注入）。成功后用响应回写，避免本地猜版本号。 */
async function onActivate(code: string): Promise<void> {
  activating.value = true
  error.value = ''
  try {
    const next = await request<{ activeSkillCode: string; configVersion: number }>(
      '/api/admin/humanize-skills/active',
      {
        method: 'PUT',
        body: JSON.stringify({ activeSkillCode: code || null, expectedConfigVersion: configVersion.value }),
      },
    )
    if (next) {
      activeSkillCode.value = next.activeSkillCode || ''
      configVersion.value = next.configVersion
    }
  } catch (err: unknown) {
    error.value = conflictMessage(err, '激活切换失败')
  } finally {
    activating.value = false
  }
}

function openEdit(skill: AdminSkill): void {
  editing.value = skill
  draft.value = { description: skill.description, promptContent: skill.promptContent, enabled: skill.enabled }
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

/** 409 统一口径（同 #57 S3）：提示刷新后重试。 */
function conflictMessage(err: unknown, fallback: string): string {
  if (err instanceof GrasslandHttpError && err.status === 409) {
    return '已被他人修改，请刷新后重试'
  }
  return err instanceof Error ? err.message : fallback
}

onMounted(() => { void load() })
</script>

<style scoped>
.panel-toolbar { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-md); }
.panel-toolbar h3 { margin: 0; }
.panel-toolbar p { margin-top: 4px; max-width: 62ch; color: var(--color-text-muted); font-size: 0.82rem; }
.refresh-btn { min-height: 32px; padding: 0 12px; border-radius: var(--radius-sm); border: 1px solid var(--color-border); background: transparent; color: var(--color-text-secondary); font-size: 0.78rem; cursor: pointer; }
.refresh-btn:disabled { opacity: 0.6; cursor: not-allowed; }

.activate-card { margin: var(--space-md) 0; padding: var(--space-md); border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--surface-muted); }
.activate-card legend { padding: 0 6px; color: var(--color-text-secondary); font-size: 0.78rem; font-weight: 600; }
.activate-option { display: flex; align-items: baseline; gap: 8px; padding: 6px 0; cursor: pointer; }
.activate-option input { accent-color: var(--color-accent); }
.activate-option input:disabled { cursor: not-allowed; }
.activate-label { font-size: 0.86rem; font-weight: 600; color: var(--color-text); }
.activate-hint { color: var(--color-text-muted); font-size: 0.78rem; }

.table-card { border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); }
.table-scroll { overflow-x: auto; }
.skills-table { width: 100%; border-collapse: collapse; font-size: 0.84rem; }
.skills-table th { text-align: left; padding: 10px 12px; color: var(--color-text-muted); font-size: 0.76rem; font-weight: 600; border-bottom: 1px solid var(--color-border); white-space: nowrap; }
.skills-table td { padding: 10px 12px; border-bottom: 1px solid var(--color-border); vertical-align: top; }
.skills-table code { font-family: var(--font-mono); font-size: 0.76rem; color: var(--color-text-secondary); }
.td-name { font-weight: 600; white-space: nowrap; }
.td-desc { color: var(--color-text-muted); max-width: 320px; }
.td-source { white-space: nowrap; }
.td-time { white-space: nowrap; color: var(--color-text-muted); font-size: 0.78rem; }
.td-empty { text-align: center; padding: var(--space-xl); color: var(--color-text-muted); margin: 0; }
.source-link { color: var(--color-text-secondary); font-size: 0.78rem; }
.license-badge { display: inline-block; margin-left: 6px; padding: 3px 7px; border: 1px solid var(--color-border); border-radius: var(--radius-pill); background: var(--surface-muted); font-size: 0.76rem; }
.panel-note { margin: var(--space-sm) 0 0; color: var(--color-text-muted); font-size: 0.78rem; }

.switch-toggle { display: inline-flex; align-items: center; gap: 6px; font-size: 0.78rem; color: var(--color-text-secondary); cursor: pointer; }
.switch-toggle input { accent-color: var(--color-accent); width: 16px; height: 16px; }
.edit-btn { min-height: 30px; padding: 0 12px; border-radius: var(--radius-sm); border: 1px solid var(--color-border); background: transparent; color: var(--color-text-secondary); font-size: 0.78rem; cursor: pointer; }
.edit-btn:hover { border-color: var(--color-border-hover); background: var(--color-surface-hover); }

.modal-form { display: flex; flex-direction: column; gap: var(--space-sm); }
.modal-subject { margin: 0; color: var(--color-text-secondary); font-size: 0.84rem; }
.field-label { display: flex; flex-direction: column; gap: 4px; color: var(--color-text-secondary); font-size: 0.8rem; }
.field-label input, .field-label textarea { padding: 8px 10px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); font: inherit; font-size: 0.84rem; }
.field-label textarea { resize: vertical; line-height: 1.6; }
.char-count { margin: 0; color: var(--color-text-muted); font-size: 0.76rem; text-align: right; }
.modal-warning { margin: 0; color: var(--color-text-muted); font-size: 0.78rem; }
.loading-state { padding: var(--space-xl); text-align: center; color: var(--color-text-muted); font-size: 0.9rem; }
.error-msg { padding: var(--space-sm) var(--space-md); border-radius: var(--radius-sm); background: color-mix(in srgb, var(--color-danger) 10%, transparent); border: 1px solid color-mix(in srgb, var(--color-danger) 20%, transparent); color: var(--color-danger); font-size: 0.86rem; margin: 0; }
</style>
