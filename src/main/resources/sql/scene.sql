-- scene 场景标签图层 SQL View 模板。
-- TODO: 将 replace_with_scene_source 替换为真实表名或视图名。
SELECT
  grid_id,
  geom_polygon,
  total_num
FROM replace_with_scene_source
WHERE batch_id = CAST('%batchId%' AS BIGINT)
  AND ('%county%' = '-1' OR county = CAST('%county%' AS INTEGER))
  AND ('%ptype%' = 'all' OR ptype = ANY (string_to_array('%ptype%', '|')))
