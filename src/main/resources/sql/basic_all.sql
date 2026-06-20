-- basic_all 图层 SQL View 模板。
-- TODO: 将 replace_with_basic_all_source 替换为真实表名或视图名。
-- 返回字段必须保持为 grid_id、geom_polygon、total_num。
SELECT
  grid_id,
  geom_polygon,
  total_num
FROM replace_with_basic_all_source
WHERE batch_id = CAST('%batchId%' AS BIGINT)
