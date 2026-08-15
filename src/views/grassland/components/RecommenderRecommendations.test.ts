// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
import RecommenderRecommendations from './RecommenderRecommendations.vue'
import type { RecommenderMatch } from '../../../types/grassland'

const match: RecommenderMatch = {
  accountId: '12345678-aaaa-bbbb-cccc-123456789abc',
  totalScore: 82,
  level: 'Lv3',
  reputationPolicyVersion: 4,
  computedAt: '2026-08-15T12:00:00Z',
  reasons: ['同平台有 3 次履约经历', '历史完成率 90%'],
  invitation: null,
  dimensions: [
    ['platformFit', '平台契合度', 30, 30], ['level', '等级', 8, 15],
    ['completionRate', '完成率', 18, 20], ['averageRating', '平均评分', 12, 15],
    ['responseSpeed', '响应速度', 8, 10], ['recentActivity', '近期活跃', 6, 10],
  ].map(([key, label, score, maxScore]) => ({
    key: key as RecommenderMatch['dimensions'][number]['key'], label: label as string,
    score: score as number, maxScore: maxScore as number, evidence: {}, reason: label as string,
  })),
}

describe('RecommenderRecommendations', () => {
  test('renders explainable score and emits invite', async () => {
    const wrapper = mount(RecommenderRecommendations, {
      props: { items: [match], eligibleCount: 1, scoringVersion: 'deterministic-v1', loading: false, invitingAccountId: '' },
    })

    expect(wrapper.text()).toContain('82')
    expect(wrapper.text()).toContain('同平台有 3 次履约经历')
    expect(wrapper.text()).toContain('平台 30/30')
    const inviteButton = wrapper.findAll('button').find((button) => button.text() === '邀请')
    expect(inviteButton).toBeDefined()
    await inviteButton!.trigger('click')
    expect(wrapper.emitted('invite')?.[0]).toEqual([match])
  })

  test('shows existing invitation instead of a second invite button', () => {
    const wrapper = mount(RecommenderRecommendations, {
      props: {
        items: [{ ...match, invitation: {
          id: 'invite-1', taskId: 'task-1', recommenderAccountId: match.accountId,
          scoringVersion: 'deterministic-v1', createdAt: '2026-08-15T12:00:00Z', appliedAt: null,
        } }], eligibleCount: 1, scoringVersion: 'deterministic-v1', loading: false, invitingAccountId: '',
      },
    })

    expect(wrapper.text()).toContain('已邀请')
    expect(wrapper.findAll('button').filter((button) => button.text() === '邀请')).toHaveLength(0)
  })
})
