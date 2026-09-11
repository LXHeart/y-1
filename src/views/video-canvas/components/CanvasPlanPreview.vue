<script setup lang="ts">
/**
 * 任务书 #100 C100-18：计划差异预览——edit 显示逐字段差异/追加；variant 显示派生影响；
 * prepare-generation 只显示准备参数并指向现有提交按钮（媒体请求由用户发起）。
 */
import type { CanvasEditAction, CanvasPlanAction } from '../../../types/video-canvas'

/** 模板统一视图：update/append 二形态摊平（差异渲染与影响面展示）。 */
interface EditItemView {
  kind: string
  patch?: Record<string, unknown>
  shot?: { visual?: string; narration?: string; plannedSeconds?: number }
}

function editItems(action: CanvasPlanAction): EditItemView[] {
  if (action.kind !== 'edit') {
    return []
  }
  return action.actions.map((item: CanvasEditAction) => item.kind === 'update-shot'
    ? { kind: 'update-shot', patch: item.patch as unknown as Record<string, unknown> }
    : { kind: 'append-shot', shot: item.shot })
}

defineProps<{
  action: CanvasPlanAction
  planStatus: string
}>()

const PATCH_FIELD_LABELS: Record<string, string> = {
  visual: '画面', narration: '旁白', plannedSeconds: '时长（秒）',
  cameraMove: '运镜', anchorImageIndex: '锚定图序号',
}
</script>

<template>
  <div class="plan-preview" data-test="canvas-plan-preview">
    <template v-if="action.kind === 'edit'">
      <h4 class="field-label">修改差异（{{ editItems(action).length }} 项）</h4>
      <ul class="plan-diffs">
        <li v-for="(item, index) in editItems(action)" :key="index" class="plan-diff"
          :data-test="`canvas-plan-diff-${index}`">
          <template v-if="item.kind === 'update-shot' && item.patch">
            <span class="badge badge-accent">修改镜头</span>
            <span v-for="(value, field) in item.patch" :key="field" class="field-note">
              <template v-if="field !== 'shotId'">
                {{ PATCH_FIELD_LABELS[field] ?? field }} → {{ value }}
              </template>
            </span>
          </template>
          <template v-else>
            <span class="badge badge-info">末尾追加镜头</span>
            <span class="field-note">
              画面「{{ item.shot?.visual }}」· 旁白「{{ item.shot?.narration }}」·
              {{ item.shot?.plannedSeconds }}s
            </span>
          </template>
        </li>
      </ul>
    </template>

    <template v-else-if="action.kind === 'variant'">
      <h4 class="field-label">派生独立方案</h4>
      <p class="field-note" data-test="canvas-plan-variant">
        新建方案「{{ action.title }}」，包含 {{ action.shotIds?.length ?? 0 }} 个选中镜头的完整副本
        ——当前方案不受影响。
      </p>
    </template>

    <template v-else>
      <h4 class="field-label">准备生成</h4>
      <p class="field-note" data-test="canvas-plan-prepare">
        模式：{{ action.mode }}<template v-if="action.shotId">（镜头 {{ action.shotId }}）</template>
        ——这只是准备动作；请点击运行栏的「发起制作/重抽」按钮启动生成并计费。
      </p>
    </template>
  </div>
</template>
