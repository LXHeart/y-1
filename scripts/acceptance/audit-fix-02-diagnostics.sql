-- 审查修复 02（C02-D 历史诊断）：裁决与协商权限只读诊断。
-- 全部为 SELECT（零写入）；未经事实核验不自动删票、重判或回滚历史资金，
-- 本阶段只保证新操作安全并提供可审阅的历史处置清单。
-- 五服务共库逻辑隔离（同 public schema）是跨域 JOIN 的部署事实。
-- 用法：psql "$DATABASE_URL" -f scripts/acceptance/audit-fix-02-diagnostics.sql
-- 产物（隔离库演练输出）：test-artifacts/audit-fix-02/diagnostics-output.txt

\echo '===== R04-D1 已有面板包含本案当事人（原告/被告担任了自己案件的审判官）====='
SELECT d.id::text AS dispute_id, d.status, d.round, d.opened_by_account_id::text AS claimant,
       d.respondent_account_id::text AS respondent, p.round AS panel_round,
       CASE WHEN p.judge_account_id = d.opened_by_account_id THEN 'claimant'
            ELSE 'respondent' END AS conflicted_party,
       p.assigned_at
  FROM dispute_case d
  JOIN dispute_panel_assignment p ON p.dispute_id = d.id
 WHERE p.judge_account_id = d.opened_by_account_id
    OR (d.respondent_account_id IS NOT NULL AND p.judge_account_id = d.respondent_account_id)
 ORDER BY d.created_at, p.round;

\echo '===== R04-D1b 上述席位的可审阅处置清单（不执行；仅列出争议/轮次/票数，供人工裁决是否重审）====='
SELECT d.id::text AS dispute_id, d.status, d.round, d.appeal_state,
       (SELECT COUNT(*) FROM dispute_vote v
         WHERE v.dispute_id = d.id AND v.round IN (
               SELECT p2.round FROM dispute_panel_assignment p2
                WHERE p2.dispute_id = d.id
                  AND (p2.judge_account_id = d.opened_by_account_id
                       OR (d.respondent_account_id IS NOT NULL
                           AND p2.judge_account_id = d.respondent_account_id)))) AS conflicted_panel_votes,
       d.created_at
  FROM dispute_case d
 WHERE EXISTS (SELECT 1 FROM dispute_panel_assignment p
                WHERE p.dispute_id = d.id
                  AND (p.judge_account_id = d.opened_by_account_id
                       OR (d.respondent_account_id IS NOT NULL
                           AND p.judge_account_id = d.respondent_account_id)))
 ORDER BY d.created_at;

\echo '===== R06-D2 未来质证截止却已提前 voting（跳过质证开庭的历史案件）====='
-- 口径：court 通道、round>0（已开庭），质证截止仍在未来、且双方未双 done——
-- 该形态在修复后不可能再出现（手动/自愈共用条件转换），命中即为历史遗留。
SELECT d.id::text AS dispute_id, d.status, d.round, d.channel,
       d.evidence_deadline, d.claimant_done_at, d.respondent_done_at,
       d.updated_at AS opened_voting_at,
       (SELECT COUNT(*) FROM dispute_panel_assignment p WHERE p.dispute_id = d.id AND p.round = d.round) AS panel_size
  FROM dispute_case d
 WHERE d.channel = 'court'
   AND d.round > 0
   AND d.evidence_deadline > now()
   AND NOT (d.claimant_done_at IS NOT NULL AND d.respondent_done_at IS NOT NULL)
 ORDER BY d.updated_at;

\echo '===== R06-D2b 空质证截止却已开庭的存量行（依赖兼容锚点，列出以便回补 deadline）====='
SELECT d.id::text AS dispute_id, d.status, d.round, d.channel, d.created_at,
       d.claimant_done_at, d.respondent_done_at,
       (SELECT COUNT(*) FROM dispute_panel_assignment p WHERE p.dispute_id = d.id AND p.round = d.round) AS panel_size
  FROM dispute_case d
 WHERE d.evidence_deadline IS NULL
   AND d.round > 0
 ORDER BY d.created_at;

\echo '===== R05-D3 同侧账号完成的协商退出（商家发起但非该报名推荐官确认）====='
-- 口径：initiated_role=merchant 且 responded_by 不是该报名的推荐官 → 确认方仍在商家侧
-- （响应方当时只能是商家方或该推荐官本人——非推荐官即同侧）。含 responded_by=发起人本人的直排形态。
SELECT e.id::text AS exit_id, e.task_id::text AS task_id, e.application_id::text AS application_id,
       e.initiated_role, e.initiated_by_account_id::text AS initiated_by,
       e.responded_by_account_id::text AS responded_by, e.status AS exit_status, e.responded_at,
       a.recommender_account_id::text AS engagement_recommender,
       a.status AS application_status
  FROM exit_request e
  JOIN task_application a ON a.id = e.application_id
 WHERE e.responded_by_account_id IS NOT NULL
   AND e.initiated_role = 'merchant'
   AND e.responded_by_account_id <> a.recommender_account_id
 ORDER BY e.responded_at;

\echo '===== R05-D3b 推荐官发起但由发起人自己或非报名推荐官响应（响应方解析异常形态）====='
SELECT e.id::text AS exit_id, e.initiated_role, e.initiated_by_account_id::text AS initiated_by,
       e.responded_by_account_id::text AS responded_by, e.status AS exit_status, e.responded_at,
       a.recommender_account_id::text AS engagement_recommender
  FROM exit_request e
  JOIN task_application a ON a.id = e.application_id
 WHERE e.responded_by_account_id IS NOT NULL
   AND e.initiated_role = 'recommender'
   AND (e.responded_by_account_id = e.initiated_by_account_id
        OR e.responded_by_account_id <> a.recommender_account_id)
 ORDER BY e.responded_at;

\echo '===== 汇总统计（应始终为空集计数；非零即需人工审阅上方明细）====='
SELECT (SELECT COUNT(*) FROM dispute_panel_assignment p JOIN dispute_case d ON d.id = p.dispute_id
         WHERE p.judge_account_id = d.opened_by_account_id
            OR (d.respondent_account_id IS NOT NULL AND p.judge_account_id = d.respondent_account_id))
       AS r04_conflicted_panel_seats,
       (SELECT COUNT(*) FROM dispute_case d
         WHERE d.channel = 'court' AND d.round > 0 AND d.evidence_deadline > now()
           AND NOT (d.claimant_done_at IS NOT NULL AND d.respondent_done_at IS NOT NULL))
       AS r06_premature_hearings,
       (SELECT COUNT(*) FROM exit_request e JOIN task_application a ON a.id = e.application_id
         WHERE e.responded_by_account_id IS NOT NULL
           AND ((e.initiated_role = 'merchant' AND e.responded_by_account_id <> a.recommender_account_id)
             OR (e.initiated_role = 'recommender'
                 AND (e.responded_by_account_id = e.initiated_by_account_id
                      OR e.responded_by_account_id <> a.recommender_account_id))))
       AS r05_same_side_exits;
