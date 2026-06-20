SELECT
  grid_id,
  geom_polygon,
  total_num
FROM v_grid_finance_app
WHERE ('%city%' = '-1' OR code_city = CAST('%city%' AS INTEGER))
  AND ('%county%' = '-1' OR code_coun = CAST('%county%' AS INTEGER))
  AND ('%type%' = 'all' OR type_code = '%type%')
