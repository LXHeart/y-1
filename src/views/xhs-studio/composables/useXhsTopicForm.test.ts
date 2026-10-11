// @vitest-environment happy-dom
import { describe, expect, test } from 'vitest'
import { nextTick } from 'vue'
import { ref } from 'vue'
import {
  useXhsTopicForm,
  XHS_TOPIC_TITLE_MAX, XHS_TOPIC_ANGLE_MAX, XHS_TOPIC_SUMMARY_MIN, XHS_EXTRA_INSTRUCTIONS_MAX,
} from './useXhsTopicForm'

/**
 * 自定义选题表单（方案 §6）：校验阈值（题目非空/概要 ≥10 字，消息+聚焦目标字段）、
 * toInputs 映射（题目→topic、角度+概要→extraInstructions 拼接与 2000 字截断）、
 * 超长截断、prefill 单向同步。
 */

describe('validate', () => {
  test('题目空拒绝：错误指向 title 字段（聚焦目标）', () => {
    const form = useXhsTopicForm(ref(''))
    form.fields.summary = '概要内容足够十个字'
    expect(form.validate()).toEqual({ field: 'title', message: '请先填写选题题目' })
  })

  test('概要 9 字拒绝、10 字通过（本地阈值常量）', () => {
    const form = useXhsTopicForm(ref(''))
    form.fields.title = '通勤穿搭'
    form.fields.summary = '一二三四五六七八九'
    const rejected = form.validate()
    expect(rejected?.field).toBe('summary')
    expect(rejected?.message).toContain(String(XHS_TOPIC_SUMMARY_MIN))

    form.fields.summary = '一二三四五六七八九十'
    expect(form.validate()).toBeNull()
  })
})

describe('toInputs', () => {
  test('角度+概要按模板拼接进 extraInstructions；题目→topic', () => {
    const form = useXhsTopicForm(ref(''))
    form.fields.title = '  通通穿搭一周不重样  '
    form.fields.angle = '打工人胶囊衣橱'
    form.fields.summary = '覆盖五套 look 与配饰预算。'
    expect(form.toInputs()).toEqual({
      topic: '通通穿搭一周不重样',
      extraInstructions: '切入角度：打工人胶囊衣橱\n内容概要：覆盖五套 look 与配饰预算。',
    })
  })

  test('只填题目：extraInstructions 为空串（调用方据此清除 brief 字段）', () => {
    const form = useXhsTopicForm(ref(''))
    form.fields.title = '选题'
    form.fields.summary = '概要必须十个字以上才行'
    form.fields.summary = ''
    expect(form.toInputs().extraInstructions).toBe('')
  })

  test('extraInstructions 超 2000 字截断（对齐后端上限）', () => {
    const form = useXhsTopicForm(ref(''))
    form.fields.title = '选题'
    form.fields.summary = '长'.repeat(XHS_EXTRA_INSTRUCTIONS_MAX + 100)
    expect(form.toInputs().extraInstructions.length).toBe(XHS_EXTRA_INSTRUCTIONS_MAX)
  })

  test('题目超 40 字、角度超 20 字截断到本地阈值', () => {
    const form = useXhsTopicForm(ref(''))
    form.fields.title = '题'.repeat(XHS_TOPIC_TITLE_MAX + 10)
    form.fields.angle = '角'.repeat(XHS_TOPIC_ANGLE_MAX + 10)
    form.fields.summary = '概要足够十个字'
    const inputs = form.toInputs()
    expect(inputs.topic.length).toBe(XHS_TOPIC_TITLE_MAX)
    expect(inputs.extraInstructions).toContain('角'.repeat(XHS_TOPIC_ANGLE_MAX))
    expect(inputs.extraInstructions).not.toContain('角'.repeat(XHS_TOPIC_ANGLE_MAX + 1))
  })
})

describe('prefill 单向同步（engine.topic → 表单）', () => {
  test('topic 变化预填题目并截断；置空时清空全部字段', async () => {
    const topic = ref('')
    const form = useXhsTopicForm(topic)
    expect(form.fields.title).toBe('')

    topic.value = '热榜选中的超长热点标题'.repeat(5)
    await nextTick()
    expect(form.fields.title).toBe('热榜选中的超长热点标题'.repeat(5).slice(0, XHS_TOPIC_TITLE_MAX))

    form.fields.angle = '遗留角度'
    form.fields.summary = '遗留概要'
    topic.value = ''
    await nextTick()
    expect(form.fields.title).toBe('')
    expect(form.fields.angle).toBe('')
    expect(form.fields.summary).toBe('')
  })

  test('prefill(title) 方法直接预填（热榜 pick/refine 回填共用）', () => {
    const form = useXhsTopicForm(ref(''))
    form.prefill('  热榜标题  ')
    expect(form.fields.title).toBe('热榜标题')
  })
})
