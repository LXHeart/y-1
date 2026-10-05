import { effectScope, ref } from 'vue'
import { expect, it } from 'vitest'
import { revisionDiff, useCreationRevisionReview } from './useCreationRevisionReview'
it('保留数字/否定差异，恢复逐字原文；切稿清除快照', () => {
 const scope = effectScope(), text = ref('不是36元，是38元。'), key = ref('a')
 const review = scope.run(() => useCreationRevisionReview(text, () => key.value))!
 text.value = '是36元。'; expect(review.changed.value).toBe(true)
 review.restore(); expect(text.value).toBe('不是36元，是38元。')
 expect(revisionDiff('36元', '38元')).toEqual({ prefix: '3', removed: '6', added: '8', suffix: '元' })
 key.value = 'b'; expect(review.original.value).toBe(null); scope.stop()
})
