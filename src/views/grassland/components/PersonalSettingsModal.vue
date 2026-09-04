<template>
  <!-- 任务书 #73：工作台页签收敛为纯业务垄，账号级内容（主页与分享 / 账号与合规）归位到此共享弹窗。
       GlModal 会 Teleport 到 body——田垄层样式全在 `.gl-field xxx` 后代选择器下（src/style.css），
       插槽内容必须包一层 gl-field 恢复作用域，否则五张卡的输入框/按钮裸奔（任务抽屉同款教训）。
       2026-09-04 反馈 6/7：三节竖排一页改为「左栏分节」——主页与分享 / 账号与合规 / 举报与投诉 /
       判例库（自一级导航迁入，点开即看）。表单节用 v-show 保草稿；判例库是只读浏览，v-if 按需挂载。 -->
  <GlModal v-if="open" title="个人设置" wide scroll persistent @close="$emit('close')">
    <div class="gl-field personal-settings">
      <nav class="gl-rail" role="tablist" aria-label="个人设置分节">
        <button
          v-for="item in railItems"
          :key="item.id"
          type="button"
          role="tab"
          class="gl-rail-item"
          :class="{ 'gl-rail-active': section === item.id }"
          :aria-selected="section === item.id"
          @click="selectSection(item.id)"
        >{{ item.label }}</button>
      </nav>

      <div class="gl-rail-panel">
        <!-- 节一（仅推荐官侧）：原「主页与分享」页签两卡 -->
        <section v-show="section === 'profile'" v-if="side === 'recommender'" class="gl-zone" aria-label="主页与分享">
          <div class="gl-zone-head">
            <h4 class="gl-zone-title">主页与分享</h4>
            <p class="gl-zone-note">推荐官资料、内容风格与推广二维码</p>
          </div>
          <div class="gl-zone-body">
            <article class="gl-tile gl-tile-wide"><MyRecommenderProfileCard /></article>
            <article class="gl-tile gl-tile-wide"><RecommenderShareCard /></article>
          </div>
        </section>

        <!-- 节二（两侧共享）：原「账号与合规」页签三卡 -->
        <section v-show="section === 'account'" class="gl-zone" aria-label="账号与合规">
          <div class="gl-zone-head">
            <h4 class="gl-zone-title">账号与合规</h4>
            <p class="gl-zone-note">邮箱、登录会话与个人数据</p>
          </div>
          <div class="gl-zone-body">
            <article class="gl-tile"><EmailBindingCard /></article>
            <article class="gl-tile"><MySessionsCard /></article>
            <article class="gl-tile"><PersonalDataComplianceCard /></article>
          </div>
        </section>

        <!-- 节三（两侧共享，任务书 #74 D7）：「我的投诉」列表与兜底举报表单；
             场景化举报在工作台业务卡上，这里是无挂载点对象（content/order 等）的兜底通道 -->
        <section v-show="section === 'complaints'" class="gl-zone" aria-label="举报与投诉">
          <div class="gl-zone-head">
            <h4 class="gl-zone-title">举报与投诉</h4>
            <p class="gl-zone-note">兜底举报表单与我的投诉记录</p>
          </div>
          <div class="gl-zone-body">
            <article class="gl-tile gl-tile-wide"><ComplaintsPanel /></article>
          </div>
        </section>

        <!-- 节四（2026-09-04 反馈 7）：判例库自一级导航迁入——小法庭往期裁决案例，点开即看 -->
        <section v-if="section === 'precedents'" class="gl-zone" aria-label="判例库">
          <div class="gl-zone-head">
            <h4 class="gl-zone-title">判例库</h4>
            <p class="gl-zone-note">往期争议裁决案例，供参考学习</p>
          </div>
          <div class="gl-zone-body precedent-embed">
            <article class="gl-tile gl-tile-wide"><PrecedentLibrary /></article>
          </div>
        </section>
      </div>
    </div>
  </GlModal>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import GlModal from '../../../components/GlModal.vue'
import MyRecommenderProfileCard from '../../../components/MyRecommenderProfileCard.vue'

const RecommenderShareCard = defineAsyncComponent(() => import('../../../components/RecommenderShareCard.vue'))
const EmailBindingCard = defineAsyncComponent(() => import('../../../components/EmailBindingCard.vue'))
const MySessionsCard = defineAsyncComponent(() => import('../../../components/MySessionsCard.vue'))
const PersonalDataComplianceCard = defineAsyncComponent(() => import('../../../components/PersonalDataComplianceCard.vue'))
const ComplaintsPanel = defineAsyncComponent(() => import('./ComplaintsPanel.vue'))
const PrecedentLibrary = defineAsyncComponent(() => import('../../../components/PrecedentLibrary.vue'))

/** persistent：画像卡是编辑表单，防误触遮罩/ESC 丢草稿——关闭走右上角 ×（GlModal 语义）。 */
const props = withDefaults(defineProps<{
  open: boolean
  side: 'merchant' | 'recommender'
  /** 活动分节（受控；深链 ?settings=<section> 落点）。 */
  section?: string
}>(), { section: 'complaints' })

const emit = defineEmits<{
  close: []
  'update:section': [section: string]
}>()

type SettingsSectionId = 'profile' | 'account' | 'complaints' | 'precedents'

const railItems = computed<Array<{ id: SettingsSectionId; label: string }>>(() => [
  ...(props.side === 'recommender' ? [{ id: 'profile' as const, label: '主页与分享' }] : []),
  { id: 'account', label: '账号与合规' },
  { id: 'complaints', label: '举报与投诉' },
  { id: 'precedents', label: '判例库' },
])

/** 商家侧无「主页与分享」节：外部落入失效分节时回落到第一节。 */
const section = ref<SettingsSectionId>(normalizeSection(props.section))
watch(() => props.section, (value) => { section.value = normalizeSection(value) })
watch(() => props.side, () => { section.value = normalizeSection(section.value) })

function normalizeSection(value: string): SettingsSectionId {
  const exists = railItems.value.some((item) => item.id === value)
  return exists ? value as SettingsSectionId : railItems.value[0].id
}

function selectSection(id: SettingsSectionId): void {
  section.value = id
  emit('update:section', id)
}
</script>

<style scoped>
.personal-settings { display: block; }

/* 判例库原是整页组件（1200px 容器 + 大标题），嵌入弹窗后收一层呼吸感 */
.precedent-embed :deep(.precedent-library) { max-width: none; margin: 0; padding: 0; }
.precedent-embed :deep(.library-header) { margin-bottom: var(--space-sm); }
.precedent-embed :deep(.library-header h1) { font-size: var(--text-lg); }
</style>
