-- scene 场景标签图层 SQL View。
-- ptype 是列名参数，必须由 application.yml 中的白名单正则限制。
SELECT
  grid_id,
  geom_polygon,
  COALESCE(%ptype%, 0) AS total_num
FROM tb_grid_personalized_portrait_total
WHERE batch_id = CAST('%batchId%' AS BIGINT)
  AND ('%county%' = '-1' OR code_coun = CAST('%county%' AS INTEGER))
