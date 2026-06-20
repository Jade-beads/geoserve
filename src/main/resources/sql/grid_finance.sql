-- 图层 grid_finance 使用的 SQL View 源 SQL。
-- GeoServer 会先用 application.yml 中的 regexpValidator 校验 WMS/WMTS viewparams，
-- 再把 %city%、%county%、%type% 替换成请求参数值。
SELECT
  grid_id,
  geom_polygon,
  total_num
FROM v_grid_finance_app
-- "-1" 和 "all" 是安全默认值，表示不启用对应过滤条件。
WHERE ('%city%' = '-1' OR code_city = CAST('%city%' AS INTEGER))
  AND ('%county%' = '-1' OR code_coun = CAST('%county%' AS INTEGER))
  AND ('%type%' = 'all' OR type_code = '%type%')
