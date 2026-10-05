import { describe, expect, it } from 'vitest'
import { contractDisplayRows, contractFields, contractFormatters, requirementFields } from './task-contract'

describe('合同字段展示完整性', () => {
  it('所有合同和嵌套要求字段都有展示规则，新增字段自动生成行', () => {
    for (const field of [...contractFields, ...requirementFields]) {
      expect(contractFormatters[field.format], field.key).toBeTypeOf('function')
    }
    expect(contractDisplayRows({}).map(row => row.key)).toEqual(contractFields.map(field => field.key))
  })

  it('旧快照字段缺失与显式 null 不混淆，不从当前平台默认值补造', () => {
    const old = contractDisplayRows({ bountyCents: 1250 })
    expect(old.find(row => row.key === 'bountyCents')?.lines).toEqual(['¥12.50'])
    expect(old.find(row => row.key === 'reviewRequired')?.lines).toEqual(['本快照未记录'])
    const current = contractDisplayRows({ deliveryDeadlineDays: null, reviewRequired: false, cancelPolicy: { script: 0 } })
    expect(current.find(row => row.key === 'reviewRequired')?.lines).toEqual(['无需发布前审稿'])
    expect(current.find(row => row.key === 'deliveryDeadlineDays')?.lines[0]).toContain('本快照未记录具体天数')
    expect(current.find(row => row.key === 'cancelPolicy')?.lines).toEqual([
      '已确认脚本：0%', '合格成品：平台默认（本快照未记录比例）', '按约发布：平台默认（本快照未记录比例）',
    ])
  })

  it('展示问题、套餐、全部内容要求及阶梯金额', () => {
    const rows = contractDisplayRows({ questionText: '目标问题', questionRef: '123', commercePackageId: 'package-1',
      requirements: { mustInclude: ['门店名'], publishEndAt: '2026-11-01T00:00:00Z',
        commissionLadder: { metricKey: 'views', tiers: [{ threshold: 1000, payoutCents: 8000 }] } } })
    const lines = rows.flatMap(row => row.lines).join(' ')
    expect(lines).toContain('目标问题')
    expect(lines).toContain('package-1')
    expect(lines).toContain('必须包含：门店名')
    expect(lines).toContain('最晚发布：2026-11-01T00:00:00Z')
    expect(lines).toContain('指标：views')
    expect(lines).toContain('¥80.00')
  })
})
