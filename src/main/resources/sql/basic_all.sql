-- basic_all 人群画像 WMTS 图层 SQL View。
-- 数据由分区任务写入 tb_grid_permanent_num_total；该图层只按 batch_id 查询总数。
SELECT
  grid_id,
  geom_polygon,
  COALESCE(num, 0) AS total_num
FROM tb_grid_permanent_num_total
WHERE batch_id = CAST('%batchId%' AS BIGINT)
