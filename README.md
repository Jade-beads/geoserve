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

## Configure Real Environment Parameters

The committed `application.yml` is sanitized. It keeps only safe local defaults and empty credentials. Do not commit real GeoServer URLs, database hosts, usernames, or passwords.

Use this flow when deploying to a real environment:

1. Prepare a private environment file, CI secret set, or server startup script outside the repository.
2. Fill the required parameters listed below.
3. Start the service with those environment variables.
4. Check `GET /api/geoserver/status`.
5. Run `POST /api/geoserver/init` after the status check is healthy.

Required runtime parameters:

| Parameter | Description | Example |
| --- | --- | --- |
| `GEOSERVER_BASE_URL` | GeoServer root URL ending with `/geoserver` | `http://<host>:<port>/geoserver` |
| `GEOSERVER_USERNAME` | GeoServer REST username | `<username>` |
| `GEOSERVER_PASSWORD` | GeoServer REST password | `<password>` |
| `GEOSERVER_WORKSPACE` | Workspace to create/use | `geo_init_demo` |
| `GAUSS_DB_HOST` | GaussDB/openGauss host reachable by GeoServer | `<db-host>` |
| `GAUSS_DB_PORT` | GaussDB/openGauss port | `5432` |
| `GAUSS_DB_NAME` | Database name | `<database>` |
| `GAUSS_DB_SCHEMA` | Database schema | `public` |
| `GAUSS_DB_USERNAME` | Database username configured in GeoServer datastore | `<db-user>` |
| `GAUSS_DB_PASSWORD` | Database password configured in GeoServer datastore | `<db-password>` |

Optional parameter:

| Parameter | Description | Default |
| --- | --- | --- |
| `GEOSERVER_INIT_RUN_ON_STARTUP` | Run initialization automatically on application startup | `false` |

Example startup command:

```bash
export GEOSERVER_BASE_URL="http://<host>:<port>/geoserver"
export GEOSERVER_USERNAME="<username>"
export GEOSERVER_PASSWORD="<password>"
export GEOSERVER_WORKSPACE="geo_init_demo"
export GAUSS_DB_HOST="<db-host>"
export GAUSS_DB_PORT="5432"
export GAUSS_DB_NAME="<database>"
export GAUSS_DB_SCHEMA="public"
export GAUSS_DB_USERNAME="<db-user>"
export GAUSS_DB_PASSWORD="<db-password>"

mvn -Dmaven.repo.local=.m2/repository spring-boot:run
```

## Configuration Notes

- `geoserver.init.run-on-startup` defaults to `false`; set it to `true` only when startup initialization is intended.
- SQL View files use GeoServer `%param%` placeholders. Do not concatenate request input into Java SQL strings.
- WMTS parameterized tiles use `VIEWPARAMS` by default. Keep regex filters strict to prevent unbounded cache growth.
