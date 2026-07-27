<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useAuth } from '../composables/useAuth'
import { useGrassland } from '../composables/useGrassland'
import RecommenderReputationBadge from './RecommenderReputationBadge.vue'
import type { RecommenderProfile, RecommenderReputation, SocialAccount } from '../types/grassland'

/**
 * 推荐官「我的主页」——画像编辑 + 自己的声誉一览（PRD 六「基础信息 / 标签 / 社交平台」的编辑侧）。
 *
 * PUT 是**整份覆盖**语义：没提交的字段等于清空，不是「不改」。所以表单状态在加载时就用
 * 后端现值初始化，保存时一次性把整份发回，不存在「局部 patch」。
 *
 * 标签与社交账号收的是**数组**：输入框里逗号分隔只是录入便利，拆/合只在此组件做一次，
 * 传给后端与徽章组件的都是数组（避免前后端各拆一次、口径漂移）。
 */

const grassland = useGrassland()
const { currentUser } = useAuth()

const reputation = ref<RecommenderReputation | null>(null)
const notice = ref('')

/** 表单状态——独立于加载的画像，加载后整体赋值，保存后用返回值再整体赋值（不可变替换）。 */
const form = reactive({
  displayName: '',
  bio: '',
  contentTags: '',
  domainTags: '',
  socials: [] as SocialAccount[],
})

const canSave = computed(() =>
  form.displayName.trim().length > 0 || form.bio.trim().length > 0
  || form.contentTags.trim().length > 0 || form.domainTags.trim().length > 0
  || form.socials.some((s) => s.platform.trim() || (s.handle ?? '').trim()))

/** 逗号串 → 去空白去重的数组。后端要数组，拆分只此一处。 */
function splitTags(raw: string): string[] {
  return Array.from(new Set(
    raw.split(/[,，]/).map((t) => t.trim()).filter((t) => t.length > 0)))
}

/** 把加载到的画像灌进表单（数组 → 逗号串以便编辑）。 */
function hydrate(profile: RecommenderProfile): void {
  form.displayName = profile.displayName || ''
  form.bio = profile.bio || ''
  form.contentTags = (profile.contentTags || []).join(', ')
  form.domainTags = (profile.domainTags || []).join(', ')
  form.socials = (profile.socialAccounts || []).map((s) => ({ ...s }))
}

async function refresh(): Promise<void> {
  const [profile, rep] = await Promise.all([
    grassland.getMyRecommenderProfile(),
    // 自己的声誉也按 accountId 取——等级/完成率对本人也是「我靠不靠谱」的直观数据。
    currentUser.value ? grassland.getReputation(currentUser.value.id) : Promise.resolve(null),
  ])
  if (profile) hydrate(profile)
  if (rep) reputation.value = rep
}

watch(() => currentUser.value?.id, (accountId) => {
  reputation.value = null
  notice.value = ''
  if (accountId) refresh()
}, { immediate: true })

function addSocial(): void {
  form.socials = [...form.socials, { platform: '', handle: '', followers: null }]
}

function removeSocial(index: number): void {
  form.socials = form.socials.filter((_, i) => i !== index)
}

async function save(): Promise<void> {
  if (!canSave.value) return
  notice.value = ''
  const updated = await grassland.updateMyRecommenderProfile({
    displayName: form.displayName.trim() || undefined,
    bio: form.bio.trim() || undefined,
    contentTags: splitTags(form.contentTags),
    domainTags: splitTags(form.domainTags),
    // 丢掉全空的行；followers 空值归一为 null（后端字段可空，但 '' 不是数字）。
    socialAccounts: form.socials
      .filter((s) => s.platform.trim() || (s.handle ?? '').trim())
      .map((s) => ({
        platform: s.platform.trim(),
        handle: (s.handle ?? '').trim() || null,
        followers: s.followers === null || s.followers === undefined ? null : Number(s.followers),
      })),
  })
  if (!updated) return
  hydrate(updated)
  notice.value = '资料已保存'
}
</script>

<template>
  <article class="prof">
    <header class="prof-head">
      <h3>我的主页</h3>
      <button type="button" class="prof-quiet" :disabled="grassland.loading.value" @click="refresh">刷新</button>
    </header>

    <p v-if="grassland.error.value" class="prof-alert prof-err" role="alert">{{ grassland.error.value }}</p>
    <p v-if="notice" class="prof-alert prof-ok">{{ notice }}</p>

    <RecommenderReputationBadge :reputation="reputation" />

    <div class="prof-field">
      <label>昵称</label>
      <input v-model="form.displayName" placeholder="展示给商家的名称" />
    </div>
    <div class="prof-field">
      <label>简介</label>
      <textarea v-model="form.bio" rows="2" placeholder="一句话介绍自己的内容方向"></textarea>
    </div>
    <div class="prof-field">
      <label>内容标签</label>
      <input v-model="form.contentTags" placeholder="逗号分隔，如 美食, 探店" />
    </div>
    <div class="prof-field">
      <label>领域标签</label>
      <input v-model="form.domainTags" placeholder="逗号分隔，如 餐饮, 零售" />
    </div>

    <div class="prof-field">
      <label>社交账号<span class="prof-hint">（粉丝量为自报，平台未核验）</span></label>
      <ul class="prof-socials">
        <li v-for="(s, i) in form.socials" :key="i" class="prof-social-row">
          <input v-model="s.platform" placeholder="平台，如 抖音" />
          <input v-model="s.handle" placeholder="账号 / 主页" />
          <input v-model="s.followers" type="number" min="0" placeholder="粉丝（自报）" />
          <button type="button" class="prof-x" :disabled="grassland.loading.value" @click="removeSocial(i)">删除</button>
        </li>
      </ul>
      <button type="button" class="prof-add" :disabled="grassland.loading.value" @click="addSocial">+ 添加社交账号</button>
    </div>

    <div class="prof-actions">
      <button type="button" :disabled="grassland.loading.value || !canSave" @click="save">保存资料</button>
      <span class="prof-hint">保存为整份覆盖：留空即清空对应字段。</span>
    </div>
  </article>
</template>

<style scoped>
.prof { display: flex; flex-direction: column; gap: 10px; }
.prof-head { display: flex; justify-content: space-between; align-items: center; }
.prof-head h3 { margin: 0; font-size: 15px; }
.prof-alert { margin: 0; padding: 7px 11px; border-radius: 6px; font-size: 13px; }
.prof-err { background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.prof-ok { background: color-mix(in srgb, var(--color-success) 14%, transparent); color: var(--color-success); }
.prof-field { display: flex; flex-direction: column; gap: 4px; }
.prof-field label { font-size: 12px; opacity: 0.7; }
input, textarea { padding: 6px 10px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: 6px; font-size: 13px; font-family: inherit; }
textarea { resize: vertical; }
.prof-socials { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.prof-social-row { display: flex; gap: 6px; flex-wrap: wrap; }
.prof-social-row input { flex: 1 1 120px; min-width: 0; }
.prof-social-row input[type="number"] { flex: 0 0 110px; }
button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: 6px; cursor: pointer; font-size: 13px; }
button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.prof-add { align-self: flex-start; }
.prof-x { flex: 0 0 auto; }
.prof-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.prof-quiet { opacity: 0.75; font-size: 12px; padding: 4px 10px; }
.prof-hint { font-size: 12px; opacity: 0.6; }
</style>
