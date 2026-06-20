\set ON_ERROR_STOP on

-- 本地 PostgreSQL 联调脚本。
-- 依赖现有 public.tb_grid 与 public.tb_grid_filter_num，生成 basic.sql 使用的分区总表。
-- 使用方式示例：
--   psql "postgresql://<user>:<password>@<host>:5432/<database>" \
--     -f docs/sql/local-postgres-basic-partition.sql
-- 默认变量用于当前本地批次；也可以继续通过 -v batch_id=... 覆盖。

\if :{?batch_id}
\else
\set batch_id 202511310100
\endif

\if :{?biz_date}
\else
\set biz_date 20251131
\endif

\if :{?city_code}
\else
\set city_code 100
\endif

CREATE EXTENSION IF NOT EXISTS postgis;

DO $$
DECLARE
  grid_id_type text;
  geom_polygon_type text;
  name_city_type text;
  code_city_type text;
  name_coun_type text;
  code_coun_type text;
  population_type_type text;
  age_type_type text;
  gende_type text;
  num_type text;
BEGIN
  SELECT format_type(a.atttypid, a.atttypmod) INTO grid_id_type
  FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = 'public' AND c.relname = 'tb_grid' AND a.attname = 'grid_id';

  SELECT format_type(a.atttypid, a.atttypmod) INTO geom_polygon_type
  FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = 'public' AND c.relname = 'tb_grid' AND a.attname = 'geom_polygon';

  SELECT format_type(a.atttypid, a.atttypmod) INTO name_city_type
  FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = 'public' AND c.relname = 'tb_grid' AND a.attname = 'name_city';

  SELECT format_type(a.atttypid, a.atttypmod) INTO code_city_type
  FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = 'public' AND c.relname = 'tb_grid' AND a.attname = 'code_city';

  SELECT format_type(a.atttypid, a.atttypmod) INTO name_coun_type
  FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = 'public' AND c.relname = 'tb_grid' AND a.attname = 'name_coun';

  SELECT format_type(a.atttypid, a.atttypmod) INTO code_coun_type
  FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = 'public' AND c.relname = 'tb_grid' AND a.attname = 'code_coun';

  SELECT format_type(a.atttypid, a.atttypmod) INTO population_type_type
  FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = 'public' AND c.relname = 'tb_grid_filter_num' AND a.attname = 'population_type';

  SELECT format_type(a.atttypid, a.atttypmod) INTO age_type_type
  FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = 'public' AND c.relname = 'tb_grid_filter_num' AND a.attname = 'age_type';

  SELECT format_type(a.atttypid, a.atttypmod) INTO gende_type
  FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = 'public' AND c.relname = 'tb_grid_filter_num' AND a.attname = 'gende';

  SELECT format_type(a.atttypid, a.atttypmod) INTO num_type
  FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = 'public' AND c.relname = 'tb_grid_filter_num' AND a.attname = 'num';

  IF grid_id_type IS NULL OR geom_polygon_type IS NULL OR name_city_type IS NULL
      OR code_city_type IS NULL OR name_coun_type IS NULL OR code_coun_type IS NULL
      OR population_type_type IS NULL OR age_type_type IS NULL OR gende_type IS NULL
      OR num_type IS NULL THEN
    RAISE EXCEPTION 'Missing required columns in public.tb_grid or public.tb_grid_filter_num';
  END IF;

  IF to_regclass('public.tb_grid_filter_num_total') IS NULL THEN
    EXECUTE format(
      'CREATE TABLE public.tb_grid_filter_num_total (
        batch_id bigint NOT NULL,
        grid_id %s NOT NULL,
        geom_polygon %s,
        name_city %s,
        code_city %s,
        name_coun %s,
        code_coun %s,
        population_type %s,
        age_type %s,
        gende %s,
        num %s
      ) PARTITION BY LIST (batch_id)',
      grid_id_type, geom_polygon_type, name_city_type, code_city_type,
      name_coun_type, code_coun_type, population_type_type, age_type_type,
      gende_type, num_type
    );
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_gfn_total_geom ON public.tb_grid_filter_num_total USING GIST (geom_polygon);
CREATE INDEX IF NOT EXISTS idx_gfn_total_grid_id ON public.tb_grid_filter_num_total (grid_id);
CREATE INDEX IF NOT EXISTS idx_gfn_total_code_city ON public.tb_grid_filter_num_total (code_city);
CREATE INDEX IF NOT EXISTS idx_gfn_total_code_coun ON public.tb_grid_filter_num_total (code_coun);
CREATE INDEX IF NOT EXISTS idx_gfn_total_dim ON public.tb_grid_filter_num_total (population_type, age_type, gende);

CREATE TABLE IF NOT EXISTS public.tb_grid_filter_num_total_p:batch_id
PARTITION OF public.tb_grid_filter_num_total
FOR VALUES IN (:batch_id);

DELETE FROM public.tb_grid_filter_num_total
WHERE batch_id = :batch_id;

INSERT INTO public.tb_grid_filter_num_total (
  batch_id, grid_id, geom_polygon, name_city, code_city, name_coun, code_coun,
  population_type, age_type, gende, num
)
SELECT
  :batch_id AS batch_id,
  g.grid_id,
  g.geom_polygon,
  g.name_city,
  g.code_city,
  g.name_coun,
  g.code_coun,
  n.population_type,
  n.age_type,
  n.gende,
  n.num
FROM public.tb_grid g
JOIN public.tb_grid_filter_num n ON g.grid_id = n.grid_id
WHERE g.code_city = :city_code
  AND n.date = :'biz_date'
  AND n.population_type IN ('home', 'work');
