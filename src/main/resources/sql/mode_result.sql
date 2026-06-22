-- mode_result 模型结果 WMS 图层 SQL View。
-- type 是列名参数，必须由 application.yml 中的白名单正则限制。
SELECT
  grid_id,
  geom_polygon,
  COALESCE(%type%, 0) AS total_num
FROM tb_grid_score_result
WHERE batch_id = CAST('%batch_id%' AS BIGINT)
