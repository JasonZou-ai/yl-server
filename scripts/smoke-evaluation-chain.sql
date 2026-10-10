START TRANSACTION;
-- 模拟应用层雪花主键（真实链路由 MyBatis-Plus ASSIGN_ID 生成）
INSERT INTO yl_evaluation.eval_task (id, task_no, elder_id, org_id, assessor_id, scene, priority, status, deleted, created_by)
VALUES (990001, 'ET-SMOKE-0001', 1, 1, 2, 'HOME', 0, 1, 0, 2);

INSERT INTO yl_evaluation.eval_order (id, order_no, task_id, elder_id, org_id, assessor_id, rule_version_id,
    status, grade_upgraded, refuse_count, row_version, deleted)
VALUES (990002, 'EO-SMOKE-0001', 990001, 1, 1, 2, 1, 'DRAFT', 0, 0, 0, 0);

INSERT INTO yl_evaluation.eval_answer (id, order_id, item_id, dimension_code, option_code, score,
    is_refused, answer_state, has_evidence, row_version)
SELECT 991000 + i.id, 990002, i.id, i.dimension_code, 'A', i.score_max, 0, 1, 0, 0
FROM yl_evaluation.eval_item i;

UPDATE yl_evaluation.eval_order SET
  total_score = 90.00, grade_code = 'GRADE_INTACT', rule_sha = REPEAT('a',64),
  preliminary_grade_code = 'L0', preliminary_level_name = '能力完好',
  final_level_code = 'L0', final_level_name = '能力完好',
  grade_change_reason = NULL, status = 'SUBMITTED', submit_at = NOW(6)
WHERE id = 990002;

INSERT INTO yl_evaluation.eval_review_log (id, order_id, action, from_status, to_status, operator_id, opinion, created_at)
VALUES (990003, 990002, 'SUBMIT', 'DRAFT', 'SUBMITTED', 2, '冒烟验证', NOW(6));

INSERT INTO yl_evaluation.eval_report (id, report_no, order_id, elder_id, org_id, grade_code, total_score,
    rule_version_id, rule_sha, preliminary_grade_code, preliminary_level_name,
    final_level_code, final_level_name, level_change_reason, verify_hash, status, generated_at, retain_until, deleted)
VALUES (990004, 'RP-SMOKE-0001', 990002, 1, 1, 'GRADE_INTACT', 90.00, 1, REPEAT('a',64),
    'L0', '能力完好', 'L0', '能力完好', NULL, REPEAT('b',64), 1, NOW(6), DATE_ADD(NOW(6), INTERVAL 30 YEAR), 0);

SELECT 'TASK' k, COUNT(*) v FROM yl_evaluation.eval_task WHERE id=990001
UNION ALL SELECT 'ORDER', COUNT(*) FROM yl_evaluation.eval_order WHERE id=990002
UNION ALL SELECT 'ANSWERS', COUNT(*) FROM yl_evaluation.eval_answer WHERE order_id=990002
UNION ALL SELECT 'REPORT', COUNT(*) FROM yl_evaluation.eval_report WHERE id=990004
UNION ALL SELECT 'LOG', COUNT(*) FROM yl_evaluation.eval_review_log WHERE id=990003
UNION ALL SELECT 'ORDER_FINAL_LVL', COUNT(*) FROM yl_evaluation.eval_order WHERE id=990002 AND final_level_code='L0' AND rule_sha IS NOT NULL;
ROLLBACK;
