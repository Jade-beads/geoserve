-- basic 基础标签图层 SQL View。
-- ptype/age/gender 仍作为值过滤，分别映射 population_type、age_type、gende。
SELECT
  grid_id,
  geom_polygon,
  COALESCE(num, 0) AS total_num
FROM tb_grid_filter_num_total
WHERE batch_id = CAST('%batchId%' AS BIGINT)
  AND ('%county%' = '-1' OR code_coun = CAST('%county%' AS INTEGER))
  AND ('%ptype%' = 'all' OR population_type = ANY (string_to_array('%ptype%', '|')))
  AND ('%age%' = 'all' OR age_type = ANY (string_to_array('%age%', '|')))
  AND ('%gender%' = 'all' OR gende = ANY (string_to_array('%gender%', '|')))
