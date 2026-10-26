# OSCDB – GTFS REST API (design)

Date: 2026-10-26
Status: draft, awaiting approval

## Intent

Make the DSW21 (Dortmund) GTFS timetable archive in
`/Users/phil/Entwicklung/MOVE.digital/Daten` queryable over a REST API.
The archive is 19 GTFS zips (Jan 2023 – Aug 2026), one per timetable period.
Today the data is only usable by unzipping CSVs by hand.

This is the starting point of a platform that will grow more features later.
The first version must be small, but its structure must let new features be
added as new modules without reworking existing ones.

## Goals

- Import all GTFS zips from the data folder into one database, keeping every
  timetable period separately addressable.
- Serve read-only REST endpoints for:
  - feeds (timetable periods): list, detail (date range, version, agencies)
  - agencies of a feed
  - lines (routes) of a feed: short name, type, color, agency
  - stops (platforms) of a feed: list with name search, detail with coordinates
  - stations (platforms grouped) of a feed: list with name search, detail
    with their platforms
  - departures at a stop or a station for a given date (and optional time
    window)
- Built as a Spring Modulith application with clear module boundaries.

## Non-goals (first version)

- No web UI of any kind.
- No write endpoints for GTFS data; no editing of timetables.
- No authentication / user accounts.
- No GTFS-Realtime, no journey planning / routing.
- No `shapes.txt`, fares, transfers (not present in the feeds anyway).
- No cross-period comparison endpoints yet (but the data model must allow them).

## Anti-goals

- A monolith where modules reach into each other's tables/classes; that would
  defeat the reason for choosing Modulith.
- Imports that create duplicates or a half-loaded feed when run twice or
  interrupted.
- Departure results that are wrong because calendar exceptions or after-midnight
  times are ignored.

## Constraints and data facts

- Each zip: `agency, stops, routes, trips, stop_times, calendar,
  calendar_dates, feed_info`. No shapes.
- `2024/210824_311024_gtfs_dsw21.zip` has UPPERCASE file names
  (`STOP_TIMES.TXT`, …). File lookup inside zips must be case-insensitive.
- Zip file names are inconsistent (`3121223gtfs`, missing underscore).
  Do not parse dates from file names; use `feed_info.txt`
  (`feed_start_date`, `feed_end_date`, `feed_version`).
- IDs (`stop_id`, `trip_id`, `route_id`, `service_id`) are only unique within a
  feed. Every table is keyed by `(feed_id, <gtfs id>)`.
- `stop_times` ≈ 1.3 M rows per feed, ≈ 20–25 M rows total. ~50 MB CSV per feed.
- Times are `H:MM:SS` with single-digit hours and may exceed `24:00:00`
  (service day continues past midnight). Store as seconds since service-day
  start (int), expose as `HH:MM:SS` string plus resolved timestamp.
- Route types present: `1` (U-Bahn/Stadtbahn), `3` (bus), `405` (monorail,
  H-Bahn). Expose the raw code plus a readable label.
- Three agencies: DSW21, Dortmund Airport, H-BAHN21. Timezone Europe/Berlin.
- Feed periods are contiguous and non-overlapping in the current data.
- `trip_headsign` / `stop_headsign` are empty everywhere.
- `stops.txt` has platforms only: `location_type` and `parent_station` are
  empty; one station name maps to several stop IDs (e.g. Adelenstraße 101, 102).
- Some feeds have no `calendar_dates` rows. Trips run up to `28:xx:xx`, so the
  last service day of a feed spills into the next feed's first day.
- `feed_version` is an export timestamp, not a timetable version.
- Last feed ends 2026-08-31; no feed covers today (2026-10-26).
- Local tooling: JDK 21, Maven 3.10, Docker 29.

## Approaches considered

| | Approach | Trade-off |
|-|-|-|
| **A (chosen)** | PostgreSQL + bulk import module (`COPY`) + Modulith read modules | Needs a running Postgres (Docker locally, Testcontainers in tests). Fast import, fast indexed queries, PostGIS available later. |
| B | Embedded H2 file DB | Zero infrastructure, but slow imports and queries at 20 M+ rows; likely migration to Postgres later anyway. |
| C | No DB, parse zips into memory | Least code, but GB of RAM, re-parse on every restart, poor base for growth. |

User picked **A**.

## Decided by the user

- Spring Modulith application, REST only, no web UI.
- PostgreSQL with bulk import (approach A).
- Kotlin, built with Maven.
- The data folder is live: new GTFS zips will be added over time.
- Both platform-level and station-level stops and departures.

## Proposed design (builder's calls unless noted)

### Stack

- Kotlin (current stable), JDK 21, Maven.
- Spring Boot (current stable) + Spring Modulith (matching version).
- Spring Web MVC, Spring JDBC (`JdbcClient`) for reads. No JPA: data is
  read-only and bulk-loaded; JPA adds no value here.
- Flyway for schema migrations.
- PostgreSQL via `compose.yaml` (Spring Boot Docker Compose support) for local
  runs; Testcontainers for tests.
- springdoc-openapi for an OpenAPI spec + Swagger UI at `/swagger-ui.html`
  (an API explorer, not a product UI).

### Modules (top-level packages under `de.movedigital.oscdb`)

- `feed` – feed catalog: `feed` table, list/detail endpoints, public
  `FeedLookup` API (e.g. `findByDate(date)`, `exists(feedId)`).
- `importer` – scans the data folder, imports zips, publishes
  `FeedImported(feedId)` event. Depends on `feed` to register feeds.
- `network` – agencies, routes, stops endpoints.
- `schedule` – trips, calendar, departures endpoint.
- `shared` (open module) – common web types: error format, paging.

Modulith verification test (`ApplicationModules.of(...).verify()`) is part
of the build. Modules talk only through public APIs and events.

Table ownership: the importer writes GTFS tables for all modules via one
bulk-load path (pragmatic: `COPY` per file). To respect boundaries, each module
owns its tables in its own Flyway migrations and exposes nothing but its
API; the importer only uses a small per-module `*Loader` interface published by
`network` and `schedule` (e.g. `NetworkLoader.load(feedId, files)`), so the
SQL stays inside the owning module.

### Data model (Postgres)

- `feed(id serial, source_file, file_sha256 unique, publisher, start_date,
  end_date, version, imported_at, status)`; `status` in
  `IMPORTING | READY | FAILED`.
- `agency, route, stop, trip, stop_time, calendar, calendar_date`:
  GTFS columns as given, plus `feed_id` FK; PK `(feed_id, gtfs_id)`
  (`stop_time`: `(feed_id, trip_id, stop_sequence)`).
- `stop_time.arrival_secs / departure_secs int`.
- `station(feed_id, station_id, name, lat, lon)`; `stop.station_id` FK.
  Grouping at import, owned by `network` *(builder's call)*:
  - If a feed sets `parent_station`, use it.
  - Otherwise group stops with identical `stop_name` within a feed; a stop more
    than 500 m from the group's first stop starts a separate group.
  - `station_id` = slug of the name (`adelenstrasse`), suffix `-2`, `-3` for
    split groups. Deterministic, so the same station keeps its ID across feeds
    (helps future comparisons). Coordinates = mean of its platforms.
- Indexes: `stop_time(feed_id, stop_id, departure_secs)`,
  `trip(feed_id, service_id)`, `stop(feed_id, lower(stop_name))` (trigram index
  via `pg_trgm` for name search).
- Optionally partition `stop_time` by `feed_id` (builder's call; plain table
  with the index above is acceptable).

### Import

- Trigger: on application startup (configurable,
  `oscdb.import.on-startup=true`) plus a CLI-free admin endpoint
  `POST /admin/imports` that rescans. This is the only non-GET endpoint.
- Data folder configured by `oscdb.import.data-dir`.
- Scan `**/*.zip` recursively. Identity = SHA-256 of the file: already-imported
  hash with status `READY` is skipped; `FAILED`/`IMPORTING` leftovers are
  deleted and re-imported.
- One feed = one transaction: insert `feed` row, stream each CSV (case-insensitive
  name match) through Postgres `COPY FROM STDIN` into staging/target tables,
  convert times, mark `READY`, commit, then publish `FeedImported`.
- Overlapping feeds (e.g. a republished corrected zip) are allowed; the import
  logs a warning. Date resolution picks the feed with the latest `imported_at`
  among READY feeds covering the date (newest import wins). *(builder's call)*
- Headsign: computed at import as the stop name of each trip's last stop
  (max `stop_sequence`), stored in `trip.derived_headsign`; GTFS
  `trip_headsign` is used instead when present. *(builder's call)*
- Feeds are imported sequentially; failure in one feed marks it `FAILED`,
  logs, and continues with the next.

### REST API (all GET, JSON, prefix `/api/v1`)

- `GET /feeds` – list with id, start/end date, version, status.
- `GET /feeds/{feedId}`
- `GET /feeds/{feedId}/agencies`
- `GET /feeds/{feedId}/routes?type=&agency=`
- `GET /feeds/{feedId}/routes/{routeId}`
- `GET /feeds/{feedId}/stops?q=&page=&size=`
- `GET /feeds/{feedId}/stops/{stopId}`
- `GET /feeds/{feedId}/stations?q=&page=&size=`
- `GET /feeds/{feedId}/stations/{stationId}` – includes its platforms
- `GET /stations/{stationId}/departures?date=&from=&limit=&feedId=` – same as
  stop departures, merged over all platforms, each entry names its platform
- `GET /stops/{stopId}/departures?date=YYYY-MM-DD&from=HH:MM&limit=`
  – resolves the feed whose range contains `date`; `feedId` may be passed to
  override. Returns route short name, color, headsign, scheduled departure
  (time string + ISO timestamp in Europe/Berlin).
- `feedId` path param also accepts `current`: the feed containing today; if
  none covers today, the latest READY feed (by `end_date`). The response marks
  this with `"fallback": true` and header `OSCDB-Feed-Fallback: true`.
  *(builder's call)*
- Departures without `date` default to today and use the same `current`
  rule (incl. fallback flag). An explicit `date` no feed covers returns 404
  problem+json; no silent fallback. *(builder's call)*
- Under fallback, departures are computed for the latest date in the fallback
  feed with today's weekday; the response's `serviceDate` shows it.
  *(builder's call)*
- Errors: RFC 9457 problem+json. Lists are paged (`page`, `size`, default 50,
  max 500).

### Departure logic

Active services on date D = `calendar` rows where D in range and weekday flag
set, plus `calendar_dates` type 1 for D, minus type 2 for D. Also include the
previous service day's trips with `departure_secs >= 86400` (after-midnight
service). Timestamps computed as service-day noon minus 12 h plus seconds
(GTFS rule, handles DST). The after-midnight trips of day D-1 come from the
feed covering D-1, which may differ from the feed covering D.

### Deployment

First version is local-only (compose + `mvn spring-boot:run`). `POST
/admin/imports` is enabled only under the `local` profile
(`oscdb.import.admin-endpoint-enabled`, default `false`). Container image and
endpoint protection come when deployment is planned. *(builder's call)*

### Testing

- Modulith structure test.
- Importer integration test with Testcontainers Postgres and a small fixture
  GTFS zip (incl. uppercase file names and >24h times).
- Station grouping tests (same name, far-apart same name, `parent_station`).
- `current` fallback tests (covered date, uncovered date, explicit date 404).
- Departure logic tests covering calendar exceptions and after-midnight trips.
- Controller slice tests for request validation and error format.

## Left to the builder

- Exact package/class names, DTO shapes beyond the fields listed.
- Whether to partition `stop_time`.
- Staging-table vs direct `COPY` details.
- Logging and progress reporting during import.

## Open for later (not now)

- Cross-period comparison endpoints, geo queries (PostGIS), realtime,
  authentication, a UI.
