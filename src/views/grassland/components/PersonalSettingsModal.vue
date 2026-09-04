<template>
  <!-- 任务书 #73：工作台页签收敛为纯业务垄，账号级内容（主页与分享 / 账号与合规）归位到此共享弹窗。
       GlModal 会 Teleport 到 body——田垄层样式全在 `.gl-field xxx` 后代选择器下（src/style.css），
       插槽内容必须包一层 gl-field 恢复作用域，否则五张卡的输入框/按钮裸奔（任务抽屉同款教训）。 -->
  <GlModal v-if="open" title="个人设置" wide scroll persistent @close="$emit('close')">
    <div class="gl-field personal-settings">
      <!-- 节一（仅推荐官侧）：原「主页与分享」页签两卡原样迁入 -->
      <section v-if="side === 'recommender'" class="gl-zone" aria-label="主页与分享">
        <div class="gl-zone-head">
          <h4 class="gl-zone-title">主页与分享</h4>
          <p class="gl-zone-note">推荐官资料、内容风格与推广二维码</p>
        </div>
        <div class="gl-zone-body">
          <article class="gl-tile gl-tile-wide"><MyRecommenderProfileCard /></article>
          <article class="gl-tile gl-tile-wide"><RecommenderShareCard /></article>
        </div>
      </section>

      <!-- 节二（两侧共享）：原「账号与合规」页签三卡原样迁入 -->
      <section class="gl-zone" aria-label="账号与合规">
        <div class="gl-zone-head">
          <h4 class="gl-zone-title">账号与合规</h4>
        </div>
        <div class="gl-zone-body">
          <article class="gl-tile"><EmailBindingCard /></article>
          <article class="gl-tile"><MySessionsCard /></article>
          <article class="gl-tile"><PersonalDataComplianceCard /></article>
        </div>
      </section>
    </div>
  </GlModal>
</template>

<script setup lang="ts">
import { defineAsyncComponent } from 'vue'
import GlModal from '../../../components/GlModal.vue'
import MyRecommenderProfileCard from '../../../components/MyRecommenderProfileCard.vue'

const RecommenderShareCard = defineAsyncComponent(() => import('../../../components/RecommenderShareCard.vue'))
const EmailBindingCard = defineAsyncComponent(() => import('../../../components/EmailBindingCard.vue'))
const MySessionsCard = defineAsyncComponent(() => import('../../../components/MySessionsCard.vue'))
const PersonalDataComplianceCard = defineAsyncComponent(() => import('../../../components/PersonalDataComplianceCard.vue'))

/** persistent：画像卡是编辑表单，防误触遮罩/ESC 丢草稿——关闭走右上角 ×（GlModal 语义）。 */
defineProps<{
  open: boolean
  side: 'merchant' | 'recommender'
}>()

defineEmits<{ close: [] }>()
</script>

<style scoped>
/* 两节纵向间距（gl-field 是纯作用域钩子类，无基础样式，包裹零副作用） */
.personal-settings { display: grid; gap: var(--space-md); }
</style>
