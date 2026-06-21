-- basic 基础标签图层 SQL View。
-- ptype/age/gender 作为多选值过滤，过滤后按网格汇总人数。
SELECT
  grid_id,
  geom_polygon,
  SUM(COALESCE(num, 0)) AS total_num
FROM tb_grid_filter_num_total
WHERE batch_id = CAST('%batchId%' AS BIGINT)
  AND ('%county%' = '-1' OR code_coun::text = '%county%')
  AND ('%ptype%' = 'all' OR population_type = ANY (string_to_array('%ptype%', '|')))
  AND ('%age%' = 'all' OR age_type = ANY (string_to_array('%age%', '|')))
  AND ('%gender%' = 'all' OR gende = ANY (string_to_array('%gender%', '|')))
GROUP BY grid_id, geom_polygon
