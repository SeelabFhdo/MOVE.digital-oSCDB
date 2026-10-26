# OSCDB

Read-only REST API over GTFS feeds (DSW21 Dortmund). Kotlin, Spring Boot, Spring Modulith, PostgreSQL 17.
GTFS zips are imported idempotently (by SHA-256) into PostgreSQL via `COPY`.

## Run

Requires JDK, Maven and Docker.

```
mvn spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments=--oscdb.import.data-dir=/path/to/gtfs-zips
```

The `local` profile starts PostgreSQL from `compose.yaml` (db/user/password `oscdb`, port 5432) and enables the admin import endpoint.
Under `local`, both the API and PostgreSQL bind to `127.0.0.1` only.
The API listens on `http://localhost:8080`. Swagger UI: `/swagger-ui.html`, OpenAPI: `/v3/api-docs`.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `oscdb.import.data-dir` | `../Daten` | Directory scanned recursively for `*.zip` GTFS feeds |
| `oscdb.import.on-startup` | `true` | Import new zips at startup; already imported zips are skipped |
| `oscdb.import.admin-endpoint-enabled` | `false` (`true` in `local`) | Enables `POST /api/v1/admin/imports` |

## Endpoints

`{ref}` is a numeric feed id or `current`. `current` is the feed covering today; if none, the READY feed with the latest end date, with response header `OSCDB-Feed-Fallback: true`.

- `GET /api/v1/feeds`, `GET /api/v1/feeds/{ref}`
- `GET /api/v1/feeds/{ref}/agencies`
- `GET /api/v1/feeds/{ref}/routes`, `/routes/{routeId}`
- `GET /api/v1/feeds/{ref}/stops` (`q` name search), `/stops/{stopId}`
- `GET /api/v1/feeds/{ref}/stations` (`q` name search), `/stations/{stationId}`
- `GET /api/v1/stops/{stopId}/departures`
- `GET /api/v1/stations/{stationId}/departures`
- `POST /api/v1/admin/imports` (only if enabled)

Paging: `/feeds`, `/stops` and `/stations` take `page` (0-based, default 0) and `size` (default 50, max 500) and return `{"items", "page", "size", "total"}`. `/agencies` and `/routes` are unpaged and return a plain JSON array.

Departure endpoints take `date` (default today), `from` (`HH:mm`), `limit` (default 20, max 200) and `feedId`. Without `date`, if the chosen feed (`feedId`, else `current`) does not cover today, the latest date in that feed with today's weekday is used and the response is marked as fallback; `serviceDate` shows the date used.

Errors are RFC 9457 `application/problem+json`.

Import notes: each zip is imported once (by SHA-256); a zip whose import failed is retried on the next run. A corrupt zip or one without `feed_info.txt` gets no feed row: it is only logged and listed under `failed` in the admin import report. Overlapping feeds are allowed (the newest import wins per date); the import logs a warning.

Example: `curl 'localhost:8080/api/v1/stations/adelenstrasse/departures?date=2026-07-01&from=07:00'`

## Tests

```
mvn test
```

Needs a running Docker daemon (Testcontainers starts PostgreSQL).
