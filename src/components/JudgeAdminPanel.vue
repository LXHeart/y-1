<template>
  <section class="judge-admin" aria-label="审判官运营准入">
    <div class="subtabs" role="tablist" aria-label="审判官管理子页签">
      <button
        v-for="tab in TABS"
        :key="tab.key"
        type="button"
        role="tab"
        :aria-selected="activeTab === tab.key"
        :class="['subtab', { 'subtab-active': activeTab === tab.key }]"
        :data-testid="`judge-tab-${tab.key}`"
        @click="switchTab(tab.key)"
      >{{ tab.label }}</button>
    </div>

    <!-- ========== 准入管理 ========== -->
    <template v-if="activeTab === 'admission'">
      <div class="panel-toolbar">
        <div>
          <h3>审判官准入</h3>
          <p>当前显示 {{ judges.length }} 名候选人</p>
        </div>
        <button type="button" class="secondary-btn" :disabled="loading" @click="loadJudges(false)">刷新</button>
      </div>
      <div class="judge-search">
        <label>账号 ID
          <input v-model.trim="searchAccountId" type="text" placeholder="精确 UUID" @keyup.enter="loadJudges(false)" />
        </label>
        <button type="button" class="secondary-btn" :disabled="loading" @click="loadJudges(false)">搜索</button>
        <button v-if="searchAccountId" type="button" class="secondary-btn" :disabled="loading" @click="clearSearch">清除</button>
      </div>
      <p v-if="error" class="error-msg" role="alert">{{ error }}</p>
      <p v-if="notice" class="success-msg" role="status">{{ notice }}</p>
      <div v-if="loading" class="loading-state">加载中...</div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr><th>账号</th><th>等级</th><th>身份</th><th>入池</th><th>运营准入</th><th>原因</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="judge in judges" :key="judge.id">
              <td class="account-cell" :title="judge.accountId">{{ judge.accountId }}</td>
              <td>Lv{{ judge.eligibilityTier }}</td>
              <td>
                <span :class="judge.probation ? 'state-warn' : 'state-good'">
                  {{ judge.probation ? '见习' : '正式' }}
                </span>
                <span v-if="judge.suspendedNow" class="state-muted"> · 挂起中</span>
              </td>
              <td><span :class="judge.active ? 'state-good' : 'state-muted'">{{ judge.active ? '活跃' : '已退池' }}</span></td>
              <td><span :class="judge.opsAdmitted ? 'state-good' : 'state-warn'">{{ judge.opsAdmitted ? '已准入' : '待准入' }}</span></td>
              <td>
                <input
                  :value="reasons[judge.id] || ''"
                  data-testid="judge-reason"
                  type="text"
                  maxlength="500"
                  placeholder="必填，1-500 字"
                  @input="setReason(judge.id, $event)"
                />
              </td>
              <td class="row-actions">
                <button
                  type="button"
                  :class="judge.opsAdmitted ? 'danger-btn' : 'primary-btn'"
                  data-testid="judge-admission-toggle"
                  :disabled="savingAccountId === judge.accountId"
                  @click="changeAdmission(judge)"
                >{{ judge.opsAdmitted ? '撤销' : '准入' }}</button>
                <button type="button" class="secondary-btn" @click="loadDetail(judge.accountId)">记录</button>
              </td>
            </tr>
            <tr v-if="judges.length === 0"><td colspan="7" class="empty-cell">暂无审判官候选人</td></tr>
          </tbody>
        </table>
      </div>
      <div v-if="nextCursor && !searchAccountId" class="pagination-actions">
        <button type="button" class="secondary-btn" :disabled="loading" @click="loadJudges(true)">加载更多</button>
      </div>

      <section v-if="selected" class="audit-section" aria-labelledby="judge-audit-title">
        <div class="audit-heading">
          <div>
            <h4 id="judge-audit-title">准入记录</h4>
            <p>{{ selected.accountId }}</p>
          </div>
          <button type="button" class="icon-btn" title="关闭记录" aria-label="关闭记录" @click="selected = null">×</button>
        </div>
        <div v-if="detailLoading" class="loading-state">加载中...</div>
        <ol v-else class="audit-list">
          <li v-for="item in selected.audit || []" :key="item.id">
            <span :class="auditActionClass(item.action)">{{ auditActionLabel(item.action) }}</span>
            <strong>{{ item.reason }}</strong>
            <small>{{ item.actorAccountId }} · v{{ item.previousVersion }} → v{{ item.newVersion }} · {{ formatDateTime(item.createdAt) }}</small>
          </li>
          <li v-if="!selected.audit?.length" class="empty-cell">暂无准入变更记录</li>
        </ol>
      </section>
    </template>

    <!-- ========== 题库管理 ========== -->
    <template v-else-if="activeTab === 'questions'">
      <div class="panel-toolbar">
        <div>
          <h3>准入考试题库</h3>
          <p>任务书 #74 卡 E：Lv4 见习通道考试出题源；修改走乐观锁（version+1），下线为软删。</p>
        </div>
        <div class="toolbar-actions">
          <label class="inline-check">
            <input v-model="questionsActiveOnly" type="checkbox" @change="loadQuestions" />
            只看启用
          </label>
          <button type="button" class="primary-btn" data-testid="question-create" @click="openQuestionForm(null)">新建题目</button>
          <button type="button" class="secondary-btn" :disabled="questionsLoading" @click="loadQuestions">刷新</button>
        </div>
      </div>
      <p v-if="error" class="error-msg" role="alert">{{ error }}</p>
      <p v-if="notice" class="success-msg" role="status">{{ notice }}</p>
      <div v-if="questionsLoading" class="loading-state">加载中...</div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr><th>类目</th><th>题干</th><th>选项</th><th>正确项</th><th>状态</th><th>v</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="q in questions" :key="q.id">
              <td>{{ q.category }}</td>
              <td class="question-cell" :title="q.question">{{ q.question }}</td>
              <td class="options-cell" :title="q.options.join('／')">{{ q.options.join('／') }}</td>
              <td>{{ q.answerIndex != null ? q.options[q.answerIndex] ?? q.answerIndex : '-' }}</td>
              <td><span :class="q.active ? 'state-good' : 'state-muted'">{{ q.active ? '启用' : '已下线' }}</span></td>
              <td>{{ q.version ?? 0 }}</td>
              <td class="row-actions">
                <button type="button" class="secondary-btn" @click="openQuestionForm(q)">编辑</button>
                <button
                  v-if="q.active"
                  type="button"
                  class="danger-btn"
                  data-testid="question-deactivate"
                  :disabled="questionSavingId === q.id"
                  @click="deactivateQuestion(q)"
                >下线</button>
              </td>
            </tr>
            <tr v-if="questions.length === 0"><td colspan="7" class="empty-cell">题库为空——先新建题目，Lv4 报名考试才有题可出</td></tr>
          </tbody>
        </table>
      </div>

      <GlModal v-if="questionForm.show" title="题目编辑" persistent>
        <form class="question-form" @submit.prevent="submitQuestion">
          <label>类目（≤32 字）
            <input v-model.trim="questionForm.category" data-testid="question-form-category" type="text" maxlength="32" placeholder="如：规则 / 质证 / 职业操守" />
          </label>
          <label>题干
            <textarea v-model.trim="questionForm.question" data-testid="question-form-question" rows="3" placeholder="单选题题干" />
          </label>
          <fieldset>
            <legend>选项（至少 2 项；选中左侧圆点为正确答案）</legend>
            <div v-for="(option, index) in questionForm.options" :key="index" class="option-row">
              <label class="option-radio" :title="`设为正确答案`">
                <input
                  type="radio"
                  name="question-answer"
                  :value="index"
                  :checked="questionForm.answerIndex === index"
                  @change="questionForm.answerIndex = index"
                />
                {{ String.fromCharCode(65 + index) }}
              </label>
              <input v-model="questionForm.options[index]" type="text" maxlength="200" :placeholder="`选项 ${String.fromCharCode(65 + index)}`" />
              <button
                v-if="questionForm.options.length > 2"
                type="button"
                class="icon-btn"
                :aria-label="`移除选项 ${String.fromCharCode(65 + index)}`"
                @click="removeOption(index)"
              >×</button>
            </div>
            <button
              v-if="questionForm.options.length < 8"
              type="button"
              class="secondary-btn"
              data-testid="question-add-option"
              @click="questionForm.options.push('')"
            >添加选项</button>
          </fieldset>
          <label v-if="questionForm.editing" class="inline-check">
            <input v-model="questionForm.active" type="checkbox" />
            启用（出题池可见）
          </label>
        </form>
        <template #actions>
          <button type="button" class="secondary-btn" @click="questionForm.show = false">取消</button>
          <button type="button" class="primary-btn" data-testid="question-form-submit" :disabled="questionFormSaving" @click="submitQuestion">
            {{ questionFormSaving ? '保存中...' : questionForm.editing ? '保存修改' : '创建' }}
          </button>
        </template>
      </GlModal>
    </template>

    <!-- ========== 考试记录 ========== -->
    <template v-else-if="activeTab === 'attempts'">
      <div class="panel-toolbar">
        <div>
          <h3>考试记录</h3>
          <p>每次交卷留痕（含未及格）；及格线 80 分，不及格冷却 24 小时。</p>
        </div>
        <button type="button" class="secondary-btn" :disabled="attemptsLoading" @click="loadAttempts">刷新</button>
      </div>
      <p v-if="error" class="error-msg" role="alert">{{ error }}</p>
      <div v-if="attemptsLoading" class="loading-state">加载中...</div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr><th>时间</th><th>账号</th><th>分数</th><th>结果</th><th>答题</th></tr>
          </thead>
          <tbody>
            <tr v-for="attempt in attempts" :key="attempt.id">
              <td>{{ formatDateTime(attempt.createdAt) }}</td>
              <td class="account-cell" :title="attempt.accountId">{{ attempt.accountId }}</td>
              <td>{{ attempt.score }}</td>
              <td><span :class="attempt.passed ? 'state-good' : 'state-warn'">{{ attempt.passed ? '及格' : '未及格' }}</span></td>
              <td class="options-cell">{{ attemptSummary(attempt) }}</td>
            </tr>
            <tr v-if="attempts.length === 0"><td colspan="5" class="empty-cell">暂无考试记录</td></tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- ========== 考核看板 ========== -->
    <template v-else-if="activeTab === 'assessment'">
      <div class="panel-toolbar">
        <div>
          <h3>审判官考核</h3>
          <p>近 {{ assessment?.windowDays ?? 90 }} 天实时聚合；弃权率 &gt;40% 且分配 ≥5 次标记「建议暂停」。挂起 30 天为运营确认制（无自动定时器）。</p>
        </div>
        <button type="button" class="secondary-btn" :disabled="assessmentLoading" @click="loadAssessment">刷新</button>
      </div>
      <p v-if="error" class="error-msg" role="alert">{{ error }}</p>
      <p v-if="notice" class="success-msg" role="status">{{ notice }}</p>
      <div v-if="assessmentLoading" class="loading-state">加载中...</div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr><th>账号</th><th>分配</th><th>实投</th><th>弃权</th><th>弃权率</th><th>建议</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in assessment?.items || []" :key="row.accountId" :class="{ 'row-flagged': row.suggestSuspension }">
              <td class="account-cell" :title="row.accountId">{{ row.accountId }}</td>
              <td>{{ row.assigned }}</td>
              <td>{{ row.voted }}</td>
              <td>{{ row.abstained }}</td>
              <td>{{ (row.abstainRate * 100).toFixed(1) }}%</td>
              <td>
                <span v-if="row.suspendedNow" class="state-warn">挂起中</span>
                <span v-else-if="row.suggestSuspension" class="state-warn">建议暂停</span>
                <span v-else class="state-muted">—</span>
              </td>
              <td class="row-actions">
                <template v-if="row.suspendedNow">
                  <button
                    type="button"
                    class="primary-btn"
                    data-testid="judge-reinstate"
                    :disabled="savingAccountId === row.accountId"
                    @click="reinstateJudge(row.accountId)"
                  >恢复</button>
                </template>
                <template v-else>
                  <input
                    :value="suspensionReasons[row.accountId] || ''"
                    data-testid="suspension-reason"
                    type="text"
                    maxlength="500"
                    placeholder="挂起理由（必填）"
                    @input="setSuspensionReason(row.accountId, $event)"
                  />
                  <button
                    type="button"
                    :class="row.suggestSuspension ? 'danger-btn' : 'secondary-btn'"
                    data-testid="judge-suspend"
                    :disabled="savingAccountId === row.accountId"
                    @click="suspendJudge(row)"
                  >挂起 30 天</button>
                </template>
              </td>
            </tr>
            <tr v-if="!assessment?.items?.length"><td colspan="7" class="empty-cell">窗口内无活跃审判官分配记录</td></tr>
          </tbody>
        </table>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import GlModal from './GlModal.vue'
import { useGrassland } from '../composables/useGrassland'
import type { AdminJudge, JudgeExamQuestion, JudgeExamAttempt, JudgeAssessmentRow } from '../types/grassland'

const TABS = [
  { key: 'admission', label: '准入管理' },
  { key: 'questions', label: '题库管理' },
  { key: 'attempts', label: '考试记录' },
  { key: 'assessment', label: '考核看板' },
] as const
type TabKey = typeof TABS[number]['key']

const grassland = useGrassland()
const activeTab = ref<TabKey>('admission')
const error = ref('')
const notice = ref('')
const loadedTabs = ref(new Set<TabKey>(['admission']))

// ---------- 准入管理（原有逻辑） ----------
const judges = ref<AdminJudge[]>([])
const reasons = ref<Record<string, string>>({})
const loading = ref(false)
const detailLoading = ref(false)
const savingAccountId = ref('')
const selected = ref<AdminJudge | null>(null)
const searchAccountId = ref('')
const nextCursor = ref<string | null>(null)
let listRequestSequence = 0

// ---------- 题库管理 ----------
const questions = ref<JudgeExamQuestion[]>([])
const questionsLoading = ref(false)
const questionsActiveOnly = ref(false)
const questionSavingId = ref('')
const questionFormSaving = ref(false)
const questionForm = reactive({
  show: false,
  editing: false,
  id: '',
  category: '',
  question: '',
  options: ['', ''],
  answerIndex: 0,
  active: true,
  expectedVersion: 0,
})

// ---------- 考试记录 ----------
const attempts = ref<JudgeExamAttempt[]>([])
const attemptsLoading = ref(false)

// ---------- 考核看板 ----------
const assessment = ref<{ windowDays: number; items: JudgeAssessmentRow[] } | null>(null)
const assessmentLoading = ref(false)
const suspensionReasons = ref<Record<string, string>>({})

function switchTab(tab: TabKey): void {
  if (activeTab.value === tab) return
  activeTab.value = tab
  error.value = ''
  notice.value = ''
  if (!loadedTabs.value.has(tab)) {
    loadedTabs.value.add(tab)
    if (tab === 'questions') void loadQuestions()
    if (tab === 'attempts') void loadAttempts()
    if (tab === 'assessment') void loadAssessment()
  }
}

// ---------- 准入管理 ----------
async function loadJudges(append = false): Promise<void> {
  const requestSequence = ++listRequestSequence
  loading.value = true
  error.value = ''
  notice.value = ''
  const result = await grassland.listAdminJudges({
    limit: 50,
    cursor: append ? nextCursor.value || undefined : undefined,
    accountId: searchAccountId.value || undefined,
  })
  if (requestSequence !== listRequestSequence) return
  if (result) {
    const items = result.items.map(cloneJudge)
    judges.value = append ? [...judges.value, ...items] : items
    nextCursor.value = result.nextCursor
  }
  else error.value = grassland.error.value || '审判官列表加载失败'
  loading.value = false
}

function clearSearch(): void {
  searchAccountId.value = ''
  void loadJudges(false)
}

function cloneJudge(judge: AdminJudge): AdminJudge {
  return { ...judge, audit: judge.audit?.map((item) => ({ ...item })) }
}

function setReason(judgeId: string, event: Event): void {
  reasons.value = {
    ...reasons.value,
    [judgeId]: (event.currentTarget as HTMLInputElement).value,
  }
}

async function changeAdmission(judge: AdminJudge): Promise<void> {
  const reason = (reasons.value[judge.id] || '').trim()
  if (!reason) {
    error.value = '请填写准入原因'
    return
  }
  savingAccountId.value = judge.accountId
  error.value = ''
  notice.value = ''
  const updated = await grassland.updateJudgeAdmission(judge.accountId, {
    admitted: !judge.opsAdmitted,
    expectedVersion: judge.version,
    reason,
  })
  if (updated) {
    judges.value = judges.value.map((item) => item.id === updated.id ? cloneJudge(updated) : item)
    reasons.value = { ...reasons.value, [judge.id]: '' }
    notice.value = updated.opsAdmitted ? '审判官已准入' : '审判官准入已撤销'
    if (selected.value?.id === judge.id) await loadDetail(judge.accountId)
  } else {
    error.value = grassland.error.value || '审判官准入更新失败'
  }
  savingAccountId.value = ''
}

async function loadDetail(accountId: string): Promise<void> {
  detailLoading.value = true
  error.value = ''
  const result = await grassland.getAdminJudge(accountId)
  if (result) selected.value = cloneJudge(result)
  else error.value = grassland.error.value || '准入记录加载失败'
  detailLoading.value = false
}

/** audit 动作标签（卡 E 扩值后六种）。 */
function auditActionLabel(action: string): string {
  const labels: Record<string, string> = {
    granted: '授予', revoked: '撤销', probation: '见习',
    promoted: '转正', suspended: '挂起', reinstated: '恢复',
  }
  return labels[action] || action
}

function auditActionClass(action: string): string {
  if (action === 'granted' || action === 'promoted' || action === 'reinstated') return 'state-good'
  if (action === 'probation') return 'state-muted'
  return 'state-warn'
}

// ---------- 题库管理 ----------
async function loadQuestions(): Promise<void> {
  questionsLoading.value = true
  error.value = ''
  notice.value = ''
  const result = await grassland.listJudgeExamQuestions(questionsActiveOnly.value)
  if (result) questions.value = result.items
  else error.value = grassland.error.value || '题库加载失败'
  questionsLoading.value = false
}

function openQuestionForm(question: JudgeExamQuestion | null): void {
  error.value = ''
  questionForm.show = true
  questionForm.editing = question !== null
  questionForm.id = question?.id || ''
  questionForm.category = question?.category || ''
  questionForm.question = question?.question || ''
  questionForm.options = question?.options?.length ? [...question.options] : ['', '']
  questionForm.answerIndex = question?.answerIndex ?? 0
  questionForm.active = question?.active ?? true
  questionForm.expectedVersion = question?.version ?? 0
}

function removeOption(index: number): void {
  questionForm.options.splice(index, 1)
  if (questionForm.answerIndex >= questionForm.options.length) {
    questionForm.answerIndex = questionForm.options.length - 1
  }
}

async function submitQuestion(): Promise<void> {
  const options = questionForm.options.map((option) => option.trim())
  if (!questionForm.category) { error.value = '请填写类目'; return }
  if (!questionForm.question) { error.value = '请填写题干'; return }
  if (options.length < 2 || options.some((option) => !option)) { error.value = '选项至少 2 项且不能为空'; return }
  if (questionForm.answerIndex < 0 || questionForm.answerIndex >= options.length) { error.value = '请选择正确答案'; return }
  questionFormSaving.value = true
  error.value = ''
  notice.value = ''
  const updated = questionForm.editing
    ? await grassland.updateJudgeExamQuestion(questionForm.id, {
      category: questionForm.category,
      question: questionForm.question,
      options,
      answerIndex: questionForm.answerIndex,
      active: questionForm.active,
      expectedVersion: questionForm.expectedVersion,
    })
    : await grassland.createJudgeExamQuestion({
      category: questionForm.category,
      question: questionForm.question,
      options,
      answerIndex: questionForm.answerIndex,
    })
  questionFormSaving.value = false
  if (updated) {
    questionForm.show = false
    await loadQuestions()
    notice.value = questionForm.editing ? '题目已更新（version+1）' : '题目已创建'
  } else {
    error.value = grassland.error.value || '题目保存失败'
  }
}

async function deactivateQuestion(question: JudgeExamQuestion): Promise<void> {
  questionSavingId.value = question.id
  error.value = ''
  notice.value = ''
  const result = await grassland.deleteJudgeExamQuestion(question.id)
  questionSavingId.value = ''
  if (result) {
    await loadQuestions()
    notice.value = '题目已下线（软删，历史 attempt 不受影响）'
  } else {
    error.value = grassland.error.value || '题目下线失败'
  }
}

// ---------- 考试记录 ----------
async function loadAttempts(): Promise<void> {
  attemptsLoading.value = true
  error.value = ''
  const result = await grassland.listJudgeExamAttempts()
  if (result) attempts.value = result.items
  else error.value = grassland.error.value || '考试记录加载失败'
  attemptsLoading.value = false
}

/** answers 为后端 JSON 字符串（[{questionId, choiceIndex, correct}]），解析为「对/总」摘要。 */
function attemptSummary(attempt: JudgeExamAttempt): string {
  if (!attempt.answers) return '-'
  try {
    const answers = JSON.parse(attempt.answers) as Array<{ correct?: boolean }>
    if (!Array.isArray(answers) || answers.length === 0) return '-'
    const correct = answers.filter((item) => item.correct).length
    return `${correct}/${answers.length} 题正确`
  } catch {
    return '-'
  }
}

// ---------- 考核看板 ----------
async function loadAssessment(): Promise<void> {
  assessmentLoading.value = true
  error.value = ''
  notice.value = ''
  const result = await grassland.listJudgeAssessment()
  if (result) assessment.value = result
  else error.value = grassland.error.value || '考核数据加载失败'
  assessmentLoading.value = false
}

function setSuspensionReason(accountId: string, event: Event): void {
  suspensionReasons.value = {
    ...suspensionReasons.value,
    [accountId]: (event.currentTarget as HTMLInputElement).value,
  }
}

async function suspendJudge(row: JudgeAssessmentRow): Promise<void> {
  const reason = (suspensionReasons.value[row.accountId] || '').trim()
  if (!reason) {
    error.value = '挂起须填写理由（1-500 字）'
    return
  }
  savingAccountId.value = row.accountId
  error.value = ''
  notice.value = ''
  const result = await grassland.updateJudgeSuspension(row.accountId, true, reason)
  savingAccountId.value = ''
  if (result) {
    suspensionReasons.value = { ...suspensionReasons.value, [row.accountId]: '' }
    await loadAssessment()
    notice.value = '审判官已挂起 30 天（触发器即刻拒票，恢复后可投）'
  } else {
    error.value = grassland.error.value || '挂起失败'
  }
}

async function reinstateJudge(accountId: string): Promise<void> {
  savingAccountId.value = accountId
  error.value = ''
  notice.value = ''
  const result = await grassland.updateJudgeSuspension(accountId, false)
  savingAccountId.value = ''
  if (result) {
    await loadAssessment()
    notice.value = '审判官已恢复（audit 已记 reinstated）'
  } else {
    error.value = grassland.error.value || '恢复失败'
  }
}

function formatDateTime(value: string | null): string {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN')
}

onMounted(() => void loadJudges(false))
</script>

<style scoped>
.judge-admin { display: grid; gap: 14px; }
.subtabs { display: inline-flex; gap: 4px; padding: 4px; background: var(--surface-hover); border-radius: var(--radius-pill); width: fit-content; }
.subtab { border: 0; background: transparent; color: var(--color-text-muted); font: inherit; font-size: 13px; font-weight: 500; padding: 6px 14px; border-radius: var(--radius-sm); cursor: pointer; }
.subtab-active { background: var(--color-surface); color: var(--color-text); font-weight: 600; box-shadow: var(--shadow-glow); }
.panel-toolbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.panel-toolbar h3, .audit-heading h4 { margin: 0; font-size: 17px; }
.panel-toolbar p, .audit-heading p { margin: 5px 0 0; color: var(--color-text-muted); font-size: 13px; }
.toolbar-actions { display: flex; align-items: center; gap: 8px; }
.inline-check { display: inline-flex; align-items: center; gap: 5px; font-size: 13px; color: var(--color-text-secondary); white-space: nowrap; }
.primary-btn, .secondary-btn, .danger-btn, .icon-btn {
  min-height: 34px; padding: 0 11px; border-radius: var(--radius-sm); border: 1px solid transparent;
  font: inherit; font-weight: 600; cursor: pointer;
}
.primary-btn { background: var(--color-accent); color: var(--color-on-accent); }
.secondary-btn { background: var(--color-surface); border-color: var(--color-border); color: var(--color-text); }
.danger-btn { background: var(--color-danger); color: var(--color-on-accent); }
.icon-btn { width: 34px; padding: 0; background: transparent; color: var(--color-text-muted); font-size: 20px; }
button:disabled { opacity: .55; cursor: not-allowed; }
.error-msg, .success-msg { margin: 0; padding: 9px 11px; border-radius: var(--radius-sm); font-size: 13px; }
.error-msg { background: color-mix(in srgb, var(--color-danger) 10%, transparent); color: var(--color-danger); border: 1px solid color-mix(in srgb, var(--color-danger) 30%, transparent); }
.success-msg { background: color-mix(in srgb, var(--color-success) 10%, transparent); color: var(--color-success); border: 1px solid color-mix(in srgb, var(--color-success) 30%, transparent); }
.loading-state { padding: 24px; text-align: center; color: var(--color-text-muted); }
.judge-search { display: grid; grid-template-columns: minmax(260px, 520px) auto auto; align-items: end; gap: 8px; }
.judge-search label { display: grid; gap: 5px; color: var(--color-text-muted); font-size: 12px; }
.judge-search input { height: 34px; box-sizing: border-box; padding: 0 8px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); }
.pagination-actions { display: flex; justify-content: center; }
.table-wrap { overflow-x: auto; border: 1px solid var(--color-border); border-radius: var(--radius-sm); }
table { width: 100%; border-collapse: collapse; min-width: 900px; }
th, td { padding: 10px 11px; border-bottom: 1px solid var(--color-border); text-align: left; font-size: 13px; }
th { color: var(--color-text-muted); background: var(--color-surface-hover); font-weight: 600; }
tbody tr:last-child td { border-bottom: 0; }
tbody tr.row-flagged td { background: color-mix(in srgb, var(--color-warning) 6%, transparent); }
.account-cell { max-width: 230px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: ui-monospace, monospace; }
.question-cell { max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.options-cell { max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--color-text-muted); }
td input { width: 100%; min-width: 170px; height: 34px; box-sizing: border-box; padding: 0 8px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); }
.row-actions { display: flex; gap: 6px; white-space: nowrap; }
.state-good { color: var(--color-success); font-weight: 700; }
.state-warn { color: var(--color-warning); font-weight: 700; }
.state-muted { color: var(--color-text-muted); }
.empty-cell { padding: 24px; text-align: center; color: var(--color-text-muted); }
.audit-section { border-top: 1px solid var(--color-border); padding-top: 16px; }
.audit-heading { display: flex; align-items: flex-start; justify-content: space-between; }
.audit-list { list-style: none; margin: 12px 0 0; padding: 0; display: grid; gap: 1px; background: var(--color-border); border: 1px solid var(--color-border); border-radius: var(--radius-sm); overflow: hidden; }
.audit-list li { display: grid; grid-template-columns: 56px minmax(160px, 1fr) minmax(260px, 1.4fr); align-items: center; gap: 10px; padding: 11px; background: var(--color-surface); font-size: 13px; }
.audit-list small { color: var(--color-text-muted); }
.question-form { display: grid; gap: 14px; }
.question-form label { display: grid; gap: 5px; font-size: 13px; color: var(--color-text-secondary); }
.question-form input[type="text"], .question-form textarea {
  height: 34px; box-sizing: border-box; padding: 6px 8px; border: 1px solid var(--color-border);
  border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); font: inherit;
}
.question-form textarea { height: auto; resize: vertical; }
.question-form fieldset { border: 1px solid var(--color-border); border-radius: var(--radius-sm); padding: 12px; display: grid; gap: 8px; margin: 0; }
.question-form legend { font-size: 13px; color: var(--color-text-secondary); padding: 0 4px; }
.option-row { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: 8px; }
.option-row input[type="text"] { width: 100%; height: 34px; box-sizing: border-box; padding: 0 8px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); }
.option-radio { display: inline-flex; align-items: center; gap: 4px; font-size: 13px; }
@media (max-width: 720px) {
  .judge-search { grid-template-columns: 1fr; }
  .audit-list li { grid-template-columns: 1fr; }
  .toolbar-actions { flex-wrap: wrap; }
}
</style>
