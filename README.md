# GeoServer Init Service

JDK 8 compatible Spring Boot service for initializing GeoServer resources through HTTP REST APIs.

## What It Does

- Creates missing workspaces.
- Creates missing workspace styles from classpath SLD files.
- Creates missing PostGIS-compatible datastores for GaussDB/openGauss deployments.
- Creates missing table layers or SQL View layers.
- Assigns a default style for newly created layers.
- Creates missing GeoWebCache WMTS layer configuration with regex parameter filters.
- Never deletes or overwrites existing GeoServer resources.

## Endpoints

- `GET /api/geoserver/status`
- `POST /api/geoserver/init`

`POST /api/geoserver/init` returns a per-resource action list with `CREATED`, `SKIPPED`, or `FAILED`.

## Run

```bash
mvn -Dmaven.repo.local=.m2/repository spring-boot:run
```

The service reads `src/main/resources/application.yml`. Most values can also be overridden with environment variables.

## Configuration Notes

- `geoserver.init.run-on-startup` defaults to `false`; set it to `true` only when startup initialization is intended.
- SQL View files use GeoServer `%param%` placeholders. Do not concatenate request input into Java SQL strings.
- WMTS parameterized tiles use `VIEWPARAMS` by default. Keep regex filters strict to prevent unbounded cache growth.
