/**
 * 小红书创作台自定义选题表单（方案 §4.2 工程师 A，featureId=pick-custom）。
 *
 * 三字段（题目/角度/概要）映射真实入参：题目→`engine.topic`、角度+概要→
 * `brief.extraInstructions`（后端 CreationBriefInput 真实消费，2000 字上限）。
 * 校验阈值（题目非空、概要 ≥10 字）为纯前端本地规则（方案 §3）。
 *
 * prefill 单向同步：handoff prefill.topic（经 F1 适配层 apply）、热榜 pick、草稿恢复
 * 回填都会改写 engine.topic，watch 单向刷进表单；用户输入不回写引擎——统一在
 * 「用此选题生成」提交时写入，避免边输入边改草稿派生值。
 */
import { reactive, watch } from 'vue'
import type { Ref } from 'vue'

/** 题目长度上限（本地规则；引擎 topic 上限 200 字，这里更紧）。 */
export const XHS_TOPIC_TITLE_MAX = 40
/** 角度长度上限（本地规则）。 */
export const XHS_TOPIC_ANGLE_MAX = 20
/** 概要最小字数（本地规则，方案 §3：概要 ≥10 字）。 */
export const XHS_TOPIC_SUMMARY_MIN = 10
/** brief.extraInstructions 拼接上限（对齐后端 2000 字校验）。 */
export const XHS_EXTRA_INSTRUCTIONS_MAX = 2000

export interface XhsTopicFormFields {
  title: string
  angle: string
  summary: string
}

/** 校验错误：field 是聚焦目标（组件据此 focus 对应输入）。 */
export interface XhsTopicFormError {
  field: keyof XhsTopicFormFields
  message: string
}

export interface XhsTopicInputs {
  topic: string
  extraInstructions: string
}

export function useXhsTopicForm(topic: Ref<string>) {
  const fields = reactive<XhsTopicFormFields>({ title: '', angle: '', summary: '' })

  watch(topic, (value) => {
    if (value) prefill(value)
    else {
      fields.title = ''
      fields.angle = ''
      fields.summary = ''
    }
  }, { immediate: true })

  /** 预填题目（热榜 pick / refine 回填共用；超长截断到本地阈值）。 */
  function prefill(title: string): void {
    fields.title = title.trim().slice(0, XHS_TOPIC_TITLE_MAX)
  }

  /** 本地校验：题目必填；概要 ≥10 字。返回错误（含聚焦目标）或 null。 */
  function validate(): XhsTopicFormError | null {
    if (!fields.title.trim()) return { field: 'title', message: '请先填写选题题目' }
    if (fields.summary.trim().length < XHS_TOPIC_SUMMARY_MIN) {
      return { field: 'summary', message: `内容概要至少 ${XHS_TOPIC_SUMMARY_MIN} 字，请补充核心信息` }
    }
    return null
  }

  /** 映射真实入参：题目→topic；角度+概要按模板拼接→extraInstructions（≤2000 字）。 */
  function toInputs(): XhsTopicInputs {
    const parts: string[] = []
    const angle = fields.angle.trim().slice(0, XHS_TOPIC_ANGLE_MAX)
    if (angle) parts.push(`切入角度：${angle}`)
    const summary = fields.summary.trim()
    if (summary) parts.push(`内容概要：${summary}`)
    return {
      topic: fields.title.trim().slice(0, XHS_TOPIC_TITLE_MAX),
      extraInstructions: parts.join('\n').slice(0, XHS_EXTRA_INSTRUCTIONS_MAX),
    }
  }

  return { fields, prefill, validate, toInputs }
}
