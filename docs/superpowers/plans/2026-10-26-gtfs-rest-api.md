# OSCDB GTFS REST API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Spring Modulith (Kotlin/Maven) app that imports the DSW21 GTFS zips into PostgreSQL and serves feeds, agencies, routes, stops, stations and departures over a read-only REST API.

**Architecture:** One Spring Boot app, modules `feed`, `network`, `schedule`, `importer`, plus open module `shared`. The importer streams zip CSVs through PostgreSQL `COPY` via per-module loader interfaces, so each module keeps its own SQL and tables. Reads use `JdbcClient`; no JPA.

**Tech Stack:** Kotlin (Boot-managed version), JDK 21, Maven, Spring Boot 4.1.2, Spring Modulith 2.1.2, Spring Web MVC, Spring JDBC, Flyway, PostgreSQL 17 (+ `pg_trgm`), FastCSV (`de.siegmar:fastcsv`), springdoc-openapi 3.2.0, Testcontainers (Boot-managed), JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-10-26-gtfs-rest-api-design.md`

## Global Constraints

- Root package `de.movedigital.oscdb`; modules are its direct subpackages: `feed`, `network`, `schedule`, `importer`, `shared`.
- Modules interact only through their top-level package (public API) and events. `ApplicationModules.of(OscdbApplication::class.java).verify()` must pass after every task.
- All REST paths prefixed `/api/v1`. All GET except `POST /api/v1/admin/imports`.
- Errors are RFC 9457 `application/problem+json`.
- Lists paged with `page` (0-based, default 0) and `size` (default 50, max 500), response `{"items": [...], "page", "size", "total"}`.
- Every GTFS table has `feed_id int not null references feed(id) on delete cascade`; GTFS IDs are `text`; PK `(feed_id, <gtfs id>)`.
- GTFS times stored as `int` seconds since service-day start; exposed as `HH:MM:SS` (hours may be ≥ 24) plus ISO-8601 offset timestamp in `Europe/Berlin`.
- Timestamp rule: `ZonedDateTime.of(serviceDate, LocalTime.NOON, Europe/Berlin).minusHours(12).plusSeconds(secs)`.
- Zip entry names matched case-insensitively (`STOP_TIMES.TXT` == `stop_times.txt`).
- Feed dates come from `feed_info.txt`, never from file names.
- Config: `oscdb.import.data-dir` (default `../Daten`), `oscdb.import.on-startup` (default `true`), `oscdb.import.admin-endpoint-enabled` (default `false`; `true` in `application-local.yml`).
- A `java.time.Clock` bean (system default zone `Europe/Berlin`) is the only source of "today".
- Route type labels: `0` tram, `1` subway, `2` rail, `3` bus, `405` monorail; anything else `other`.

## Review Focus

1. Stop ID passed to departures exists in the feed for D but not in the feed for D-1 (or vice versa) → no error; the missing side just contributes nothing. Test in Task 8.
2. `q` containing `%`, `_` or `'` in stop/station search → treated literally, no SQL error. Test in Task 6.
3. Re-running the import (startup twice, or `POST /admin/imports` while nothing changed) → no duplicates, feed count unchanged. Test in Task 7.
4. A corrupt or non-GTFS zip in the data folder → that feed is `FAILED`, other feeds still import, app starts. Test in Task 7.
5. Unknown `feedId`/`stopId`/`stationId`, malformed `date`/`from` → 404/400 problem+json, never 500. Tests in Tasks 3, 6, 8.

## Decisions this plan adds to the spec

- Departures under `current` fallback (today not covered): computed for the latest date in the fallback feed with today's weekday; response field `serviceDate` shows the date used.
- The importer's `IMPORTING` feed row is committed in its own transaction; data load runs in a second transaction; failure marks the row `FAILED` in a third.
- Headsign computed in Kotlin while streaming `stop_times` (no cross-module SQL).
- Departures resolve the stop/station by ID inside each service day's feed separately.

## File Structure

```
pom.xml
compose.yaml                                   postgres:17 for local runs
src/main/resources/application.yml, application-local.yml
src/main/resources/db/migration/V1__feed.sql, V2__network.sql, V3__schedule.sql
src/main/kotlin/de/movedigital/oscdb/
  OscdbApplication.kt                          @SpringBootApplication, Clock bean
  shared/  package-info (open module) or ModuleMarker with @ApplicationModule(type = OPEN)
    web/PageResponse.kt, web/ProblemHandling.kt
    gtfs/GtfsSource.kt, gtfs/GtfsTable.kt, gtfs/GtfsTime.kt
    jdbc/PgCopy.kt
  feed/  Feed.kt, FeedLookup.kt (API), FeedRepository.kt, FeedController.kt, FeedRegistry.kt (API used by importer)
  network/  NetworkLoader.kt (API), NetworkLookup.kt (API), StationGrouper.kt, Slug.kt, NetworkRepository.kt, NetworkController.kt
  schedule/  ScheduleLoader.kt (API), ServiceCalendar.kt, DepartureService.kt, DepartureController.kt, ScheduleRepository.kt
  importer/  FeedImported.kt (API event), ZipGtfsSource.kt, ImportService.kt, ImportRunner.kt, AdminImportController.kt
src/test/kotlin/de/movedigital/oscdb/...       mirrors main
src/test/resources/gtfs/mini/*.TXT             fixture feed (uppercase names on purpose)
```

## Fixture feed `mini` (used by Tasks 5–8)

Files under `src/test/resources/gtfs/mini/`, UPPERCASE names. Test helper `Fixtures.miniZip(dir: Path): Path` zips them into a temp folder.

- `FEED_INFO.TXT`: `Dortmunder Stadtwerke AG,https://www.21.de/,de,20260101,20260131,test-1`
- `AGENCY.TXT`: agency `1`, `DSW21`, tz `Europe/Berlin`
- `ROUTES.TXT`: `1` `U41` type `1` color `FFCC00`; `2` `440` type `3` color `2B2171`
- `STOPS.TXT`: `101` Adelenstraße 51.487622,7.530952; `102` Adelenstraße 51.487477,7.531048; `103` Adelenstraße 51.520000,7.460000; `200` Hauptbahnhof 51.517800,7.459300; `300` Westfalenhallen 51.495000,7.451000
- `CALENDAR.TXT`: service `1` Mon–Fri 20260101–20260131; service `2` Sat–Sun same range
- `CALENDAR_DATES.TXT`: `1,20260106,2`; `2,20260106,1`
- `TRIPS.TXT` (headsigns empty): `T1` route `1` svc `1`; `T2` route `2` svc `1`; `T3` route `1` svc `1`
- `STOP_TIMES.TXT`: `T1`: 101 `08:00:00`/1, 200 `08:10:00`/2, 300 `8:20:00`/3; `T2`: 102 `09:00:00`/1, 200 `9:15:00`/2; `T3`: 101 `24:30:00`/1, 300 `25:00:00`/2

Derived expectations: headsigns T1/T3 `Westfalenhallen`, T2 `Hauptbahnhof`; stations `adelenstrasse` (101, 102), `adelenstrasse-2` (103), `hauptbahnhof`, `westfalenhallen`. 2026-01-01 is a Thursday; 2026-01-06 Tuesday.

---

### Task 1: Project scaffold and module structure

**Files:**
- Create: `pom.xml`, `compose.yaml`, `.gitignore`, `src/main/resources/application.yml`, `src/main/resources/application-local.yml`, `src/main/kotlin/de/movedigital/oscdb/OscdbApplication.kt`, empty module marker files for `feed`, `network`, `schedule`, `importer`, `shared` (shared marked `@ApplicationModule(type = ApplicationModule.Type.OPEN)`)
- Test: `src/test/kotlin/de/movedigital/oscdb/ModularityTests.kt`, `src/test/kotlin/de/movedigital/oscdb/TestcontainersConfiguration.kt`, `src/test/kotlin/de/movedigital/oscdb/ApplicationTests.kt`

**Interfaces:**
- Produces: `TestcontainersConfiguration` (`@TestConfiguration` with `@ServiceConnection PostgreSQLContainer("postgres:17")`), used by every integration test via `@Import`. Test `application.yml` override: `oscdb.import.on-startup=false`.

- [ ] **Step 1:** `git init` in `OSCDB`; `.gitignore` with `target/`, `.idea/`, `*.iml`, `.DS_Store`.
- [ ] **Step 2:** Write `pom.xml`: parent `spring-boot-starter-parent:4.1.2`; BOM `spring-modulith-bom:2.1.2`; deps `spring-boot-starter-webmvc`, `spring-boot-starter-jdbc`, `spring-boot-starter-flyway`, `flyway-database-postgresql`, `postgresql`, `spring-modulith-starter-core`, Jackson Kotlin module (Boot 4 = Jackson 3, `tools.jackson.module:jackson-module-kotlin`), `kotlin-reflect`, `de.siegmar:fastcsv` (latest), `springdoc-openapi-starter-webmvc-ui:3.2.0`, `spring-boot-docker-compose` (optional, runtime); test: `spring-boot-starter-webmvc-test`, `spring-boot-testcontainers`, `org.testcontainers:testcontainers-postgresql`, `spring-modulith-starter-test`. `kotlin-maven-plugin` with `spring` all-open plugin, `-Xjsr305=strict`, jvmTarget 21.
- [ ] **Step 3:** Write the failing tests: `ModularityTests.verifiesModules()` calls `ApplicationModules.of(OscdbApplication::class.java).verify()`; `ApplicationTests.contextLoads()` with `@SpringBootTest @Import(TestcontainersConfiguration::class)`.
- [ ] **Step 4:** Run `mvn -q test` → FAIL (no application class).
- [ ] **Step 5:** Add `OscdbApplication` with `@Bean fun clock(): Clock = Clock.system(ZoneId.of("Europe/Berlin"))`; `application.yml` with the three `oscdb.import.*` defaults from Global Constraints and `spring.docker.compose.enabled=false`; `application-local.yml` with `oscdb.import.admin-endpoint-enabled: true` and `spring.docker.compose.enabled: true`; `compose.yaml` with `postgres:17`, db/user/password `oscdb`, port 5432.
- [ ] **Step 6:** Run `mvn -q test` → PASS.
- [ ] **Step 7:** Commit `chore: scaffold Spring Modulith Kotlin project`.

### Task 2: Shared GTFS reading, time parsing and COPY helper

**Files:**
- Create: `shared/gtfs/GtfsSource.kt`, `shared/gtfs/GtfsTable.kt`, `shared/gtfs/GtfsTime.kt`, `shared/jdbc/PgCopy.kt`
- Test: `shared/gtfs/GtfsTimeTest.kt`, `shared/gtfs/GtfsTableTest.kt`, `shared/jdbc/PgCopyIT.kt`

**Interfaces:**
- Produces:
  - `interface GtfsSource { fun open(fileName: String): InputStream? }` – `fileName` like `"stops.txt"`, lookup case-insensitive, `null` if absent.
  - `object GtfsTable { fun read(input: InputStream, consumer: (GtfsRow) -> Unit) }`; `class GtfsRow { operator fun get(column: String): String?` (blank → null) `}`. Strips UTF-8 BOM; columns by header name.
  - `object GtfsTime { fun toSeconds(value: String): Int; fun format(secs: Int): String }`
  - `class PgCopy(conn: java.sql.Connection) { fun into(table: String, columns: List<String>, block: (CopyRowWriter) -> Unit): Long }`; `CopyRowWriter.row(vararg values: Any?)` writes one CSV row (null → empty unquoted, strings quoted). Uses `conn.unwrap(PGConnection::class.java).copyAPI.copyIn("COPY $table (...) FROM STDIN (FORMAT csv)")`.

- [ ] **Step 1:** Write failing tests:
  - `GtfsTimeTest`: `toSeconds("2:58:42") == 10722`, `toSeconds("08:00:00") == 28800`, `toSeconds("25:00:00") == 90000`, `format(90000) == "25:00:00"`, `format(10722) == "02:58:42"`, `toSeconds("abc")` throws `IllegalArgumentException`.
  - `GtfsTableTest`: header `"﻿stop_id,stop_name\n101,Adelenstraße\n102,\n"` → rows `[101, Adelenstraße]`, `[102, null]`; `row["missing"] == null`.
  - `PgCopyIT` (Testcontainers): create temp table `t(a text, b int)`, copy rows `("x,\"y", 1)`, `(null, 2)` → select returns exactly those values, return value `2`.
- [ ] **Step 2:** Run `mvn -q test -Dtest='GtfsTimeTest,GtfsTableTest,PgCopyIT'` → FAIL.
- [ ] **Step 3:** Implement with FastCSV `CsvReader` (header-aware) and `CopyIn` streaming through a buffered writer.
- [ ] **Step 4:** Run same command → PASS; run `ModularityTests` → PASS.
- [ ] **Step 5:** Commit `feat(shared): GTFS CSV reading, time parsing and COPY helper`.

### Task 3: Feed module – catalog, lookup, REST and error format

**Files:**
- Create: `db/migration/V1__feed.sql`, `feed/Feed.kt`, `feed/FeedLookup.kt`, `feed/FeedRegistry.kt`, `feed/FeedRepository.kt`, `feed/FeedController.kt`, `shared/web/PageResponse.kt`, `shared/web/ProblemHandling.kt`
- Test: `feed/FeedLookupIT.kt`, `feed/FeedControllerIT.kt`

**Interfaces:**
- Produces:
  - `V1__feed.sql`: table `feed(id serial pk, source_file text not null, file_sha256 text not null unique, publisher text, start_date date not null, end_date date not null, version text, imported_at timestamptz, status text not null check in ('IMPORTING','READY','FAILED'))`.
  - `data class Feed(id: Int, sourceFile: String, publisher: String?, startDate: LocalDate, endDate: LocalDate, version: String?, importedAt: Instant?, status: FeedStatus)`; `enum FeedStatus { IMPORTING, READY, FAILED }`.
  - `data class FeedResolution(feed: Feed, fallback: Boolean)`
  - `interface FeedLookup { fun findById(id: Int): Feed?; fun findByDate(date: LocalDate): Feed?; fun current(): FeedResolution?; fun resolve(ref: String): FeedResolution }` – `findByDate`: READY, `start<=date<=end`, newest `imported_at` wins. `current()`: `findByDate(today)` else READY feed with max `end_date` with `fallback=true`; `null` if no READY feed. `resolve(ref)`: `"current"` → `current()`, numeric → `findById` (READY only) with `fallback=false`; otherwise throws `NotFoundException`.
  - `interface FeedRegistry { fun statusOf(sha256: String): FeedStatus?; fun deleteBySha(sha256: String); fun register(sourceFile: String, sha256: String, info: FeedInfo): Int; fun markReady(id: Int); fun markFailed(id: Int) }`; `data class FeedInfo(publisher: String?, startDate: LocalDate, endDate: LocalDate, version: String?)`. `register` inserts `IMPORTING`; `markReady` sets `imported_at=now()`.
  - `shared.web`: `data class PageResponse<T>(items: List<T>, page: Int, size: Int, total: Long)`; `data class PageRequest(page: Int, size: Int)` with `companion fun of(page: Int?, size: Int?): PageRequest` (defaults 0/50, `size` > 500 or < 1 or `page` < 0 → `BadRequestException`); `class NotFoundException(detail: String)`, `class BadRequestException(detail: String)`; `@RestControllerAdvice` mapping them, `MethodArgumentTypeMismatchException` and `DateTimeParseException` to 404/400 `ProblemDetail`.
  - REST: `GET /api/v1/feeds` → `PageResponse<FeedDto>`, all statuses; `GET /api/v1/feeds/{feedRef}` → `FeedDto` + `fallback` field + header `OSCDB-Feed-Fallback: true` when fallback. `FeedDto(id, sourceFile, startDate, endDate, version, status, importedAt)`.

- [ ] **Step 1:** Write failing tests. `FeedLookupIT` (insert rows via `FeedRegistry`, `@MockitoBean`/test `Clock` fixed at 2026-10-26 Europe/Berlin):
  - two READY feeds both covering 2026-01-10, second registered later → `findByDate(2026-01-10)` returns the second.
  - IMPORTING feed covering a date is not returned.
  - feeds ending 2026-08-31 and 2026-06-13 → `current()` returns the 2026-08-31 one with `fallback == true`.
  - feed covering 2026-10-26 → `current().fallback == false`.
  - `resolve("abc")` and `resolve("999")` throw `NotFoundException`.
  `FeedControllerIT` (MockMvc): `/api/v1/feeds/current` has header `OSCDB-Feed-Fallback: true` and `$.fallback == true`; `/api/v1/feeds/999` → 404 with `Content-Type: application/problem+json`; `/api/v1/feeds?size=501` → 400.
- [ ] **Step 2:** Run `mvn -q test -Dtest='Feed*IT'` → FAIL.
- [ ] **Step 3:** Implement repository with `JdbcClient`, lookup, registry, controller, problem handling.
- [ ] **Step 4:** Run same command → PASS; `ModularityTests` → PASS.
- [ ] **Step 5:** Commit `feat(feed): feed catalog, lookup with current fallback, REST`.

### Task 4: Network module – schema, station grouping and loader

**Files:**
- Create: `db/migration/V2__network.sql`, `network/NetworkLoader.kt`, `network/StationGrouper.kt`, `network/Slug.kt`, `network/JdbcNetworkLoader.kt`
- Test: `network/SlugTest.kt`, `network/StationGrouperTest.kt`, `network/NetworkLoaderIT.kt`

**Interfaces:**
- Consumes: `GtfsSource`, `GtfsTable`, `PgCopy` (Task 2).
- Produces:
  - `V2__network.sql`: `create extension if not exists pg_trgm;` `agency(feed_id, agency_id, name, url, timezone, lang, phone, fare_url)`, `route(feed_id, route_id, agency_id, short_name, long_name, type int, color, text_color)`, `station(feed_id, station_id, name, lat double, lon double)`, `stop(feed_id, stop_id, code, name, lat, lon, location_type int, parent_station, wheelchair_boarding int, station_id)`; FK `(feed_id, station_id)` → `station`; indexes `stop(feed_id, station_id)`, trigram GIN on `stop(lower(name))` and `station(lower(name))`.
  - `interface NetworkLoader { fun load(feedId: Int, source: GtfsSource, connection: Connection) }` – loads agency, routes, stations, stops in that order; missing `agency.txt`/`routes.txt`/`stops.txt` → `IllegalStateException`.
  - `fun slug(name: String): String` – lowercase, `ä→ae ö→oe ü→ue ß→ss`, other non-`[a-z0-9]` runs → single `-`, trimmed of `-`.
  - `data class StopPoint(stopId: String, name: String, lat: Double, lon: Double, parentStation: String?)`; `data class StationGroup(stationId: String, name: String, lat: Double, lon: Double, stopIds: List<String>)`; `object StationGrouper { fun group(stops: List<StopPoint>): List<StationGroup> }`.

Grouping algorithm (spec): if any stop has `parentStation`, group by it (station name = name of the parent stop if present, else first child's name; stops that are themselves parents are excluded from platform lists). Otherwise, in file order: per name, attach a stop to the first existing group of that name whose *first* stop is ≤ 500 m away (haversine, R = 6 371 000 m), else open a new group. IDs: `slug(name)`, then `-2`, `-3`… for further groups of the same name in creation order. Group lat/lon = mean of member stops.

- [ ] **Step 1:** Write failing tests:
  - `SlugTest`: `slug("Adelenstraße") == "adelenstrasse"`, `slug("Dortmund Hbf / Königswall") == "dortmund-hbf-koenigswall"`, `slug("  Ü-Bahn  ") == "ue-bahn"`.
  - `StationGrouperTest`: the five fixture stops → ids `adelenstrasse` (`101`,`102`), `adelenstrasse-2` (`103`), `hauptbahnhof`, `westfalenhallen`; `adelenstrasse` lat == mean(51.487622, 51.487477). Second case with `parentStation = "P1"` on 101 and 102 → one group with both stops regardless of distance.
  - `NetworkLoaderIT`: load fixture `mini` for a registered feed → counts agency 1, route 2, stop 5, station 4; `stop` 102 has `station_id = 'adelenstrasse'`.
- [ ] **Step 2:** Run `mvn -q test -Dtest='SlugTest,StationGrouperTest,NetworkLoaderIT'` → FAIL.
- [ ] **Step 3:** Implement; stops are read once into memory (~2 300 rows per feed), grouped, then `PgCopy` into `station` and `stop`.
- [ ] **Step 4:** Run same command → PASS; `ModularityTests` → PASS.
- [ ] **Step 5:** Commit `feat(network): schema, station grouping, bulk loader`.

### Task 5: Schedule module – schema and loader with derived headsign

**Files:**
- Create: `db/migration/V3__schedule.sql`, `schedule/ScheduleLoader.kt`, `schedule/JdbcScheduleLoader.kt`
- Test: `schedule/ScheduleLoaderIT.kt`, `src/test/kotlin/de/movedigital/oscdb/Fixtures.kt`, `src/test/resources/gtfs/mini/*.TXT`

**Interfaces:**
- Consumes: Task 2 helpers; `NetworkLookup.stopNames(feedId: Int): Map<String, String>` (add to `network/NetworkLookup.kt` here, implemented by `NetworkRepository`).
- Produces:
  - `V3__schedule.sql`: `calendar(feed_id, service_id, start_date date, end_date date, monday..sunday boolean)`, `calendar_date(feed_id, service_id, date date, exception_type int, pk(feed_id, service_id, date))`, `trip(feed_id, trip_id, route_id, service_id, headsign, derived_headsign, direction_id int, block_id, wheelchair_accessible int)`, `stop_time(feed_id, trip_id, stop_sequence int, stop_id, arrival_secs int, departure_secs int, pickup_type int, drop_off_type int, pk(feed_id, trip_id, stop_sequence))`; indexes `stop_time(feed_id, stop_id, departure_secs)`, `trip(feed_id, service_id)`.
  - `interface ScheduleLoader { fun load(feedId: Int, source: GtfsSource, connection: Connection) }` – calendar, calendar_dates (optional file), trips, stop_times. Headsign: while streaming `stop_times`, keep per trip the stop at max `stop_sequence`; after the copy, set `trip.derived_headsign` from `stopNames` with one batched `UPDATE ... FROM (VALUES ...)`, or via a temp table + COPY.
  - `object Fixtures { fun miniZip(dir: Path): Path }`.

- [ ] **Step 1:** Create fixture files (content in "Fixture feed `mini`" above) and `Fixtures.miniZip`.
- [ ] **Step 2:** Write failing `ScheduleLoaderIT` (runs `NetworkLoader` first): counts calendar 2, calendar_date 2, trip 3, stop_time 7; `T3` seq 1 `departure_secs == 88200`; `T1` seq 3 `arrival_secs == 30000`; `derived_headsign` T1 `Westfalenhallen`, T2 `Hauptbahnhof`.
- [ ] **Step 3:** Run `mvn -q test -Dtest=ScheduleLoaderIT` → FAIL.
- [ ] **Step 4:** Implement.
- [ ] **Step 5:** Run → PASS; `ModularityTests` → PASS.
- [ ] **Step 6:** Commit `feat(schedule): schema and bulk loader with derived headsign`.

### Task 6: Network REST endpoints

**Files:**
- Create: `network/NetworkController.kt`, extend `network/NetworkRepository.kt`, `network/NetworkLookup.kt`
- Test: `network/NetworkControllerIT.kt`

**Interfaces:**
- Consumes: `FeedLookup.resolve(ref)` (Task 3), `PageRequest`/`PageResponse`.
- Produces (REST, `{feedRef}` = id or `current`, fallback header as in Task 3):
  - `GET /api/v1/feeds/{feedRef}/agencies` → list `AgencyDto(id, name, url, timezone)`
  - `GET /api/v1/feeds/{feedRef}/routes?type=&agency=` → list `RouteDto(id, shortName, longName, type, typeLabel, color, textColor, agencyId)`
  - `GET /api/v1/feeds/{feedRef}/routes/{routeId}`
  - `GET /api/v1/feeds/{feedRef}/stops?q=&page=&size=` → `PageResponse<StopDto(id, name, lat, lon, stationId)>`, ordered by name, id
  - `GET /api/v1/feeds/{feedRef}/stops/{stopId}`
  - `GET /api/v1/feeds/{feedRef}/stations?q=&page=&size=` → `PageResponse<StationDto(id, name, lat, lon)>`
  - `GET /api/v1/feeds/{feedRef}/stations/{stationId}` → `StationDetailDto(id, name, lat, lon, platforms: List<StopDto>)`
  - `NetworkLookup` adds: `fun stopExists(feedId: Int, stopId: String): Boolean`, `fun platformsOf(feedId: Int, stationId: String): List<String>?` (null if station unknown), `fun routes(feedId: Int): Map<String, RouteInfo>`, `data class RouteInfo(routeId, shortName, type, color)`.
- Search: `lower(name) like '%' || escaped(lower(q)) || '%' escape '\'` with `%`, `_`, `\` escaped; uses the trigram index.

- [ ] **Step 1:** Write failing `NetworkControllerIT` (import fixture via both loaders into a READY feed):
  - `/stations?q=adelen` → total 2, ids `adelenstrasse`, `adelenstrasse-2`.
  - `/stations/adelenstrasse` → platforms `101`, `102`.
  - `/stops?q=%25` (`%`) → 200, total 0; `/stops?q=O'` → 200.
  - `/routes?type=3` → only `440`, `typeLabel == "bus"`; `/routes/1` `typeLabel == "subway"`.
  - `/stops/999` and `/stations/nope` → 404 problem+json.
- [ ] **Step 2:** Run `mvn -q test -Dtest=NetworkControllerIT` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS; `ModularityTests` → PASS.
- [ ] **Step 5:** Commit `feat(network): agencies, routes, stops, stations endpoints`.

### Task 7: Importer – scanning, idempotency, failure handling, triggers

**Files:**
- Create: `importer/FeedImported.kt`, `importer/ZipGtfsSource.kt`, `importer/ImportService.kt`, `importer/ImportRunner.kt`, `importer/AdminImportController.kt`, `importer/ImportProperties.kt`
- Test: `importer/ZipGtfsSourceTest.kt`, `importer/ImportServiceIT.kt`, `importer/AdminImportControllerIT.kt`

**Interfaces:**
- Consumes: `FeedRegistry` (Task 3), `NetworkLoader` (Task 4), `ScheduleLoader` (Task 5), `GtfsSource`, `GtfsTable`.
- Produces:
  - `data class FeedImported(val feedId: Int)` – published after `markReady`.
  - `class ZipGtfsSource(zip: Path) : GtfsSource, AutoCloseable` (`java.util.zip.ZipFile`, case-insensitive entry map).
  - `@ConfigurationProperties("oscdb.import") data class ImportProperties(dataDir: Path, onStartup: Boolean, adminEndpointEnabled: Boolean)`.
  - `class ImportService { fun importAll(): ImportReport }`; `data class ImportReport(imported: List<Int>, skipped: Int, failed: List<String>)`. Recursive `**/*.zip` scan sorted by path; SHA-256; `READY` → skip; `IMPORTING`/`FAILED` → `deleteBySha` then import; missing data dir → log warning, empty report. Per feed: `register` (own tx) → `TransactionTemplate` with `DataSourceUtils.getConnection` passing the same `Connection` to `NetworkLoader` then `ScheduleLoader` → `markReady` → publish; any exception → rollback, `markFailed`, log, continue. Log per-feed duration and row counts.
  - `ImportRunner : ApplicationRunner`, `@ConditionalOnProperty("oscdb.import.on-startup", havingValue = "true")`.
  - `AdminImportController` `POST /api/v1/admin/imports` → `200 ImportReport`; bean `@ConditionalOnProperty("oscdb.import.admin-endpoint-enabled", havingValue = "true")`.

- [ ] **Step 1:** Write failing tests:
  - `ZipGtfsSourceTest`: fixture zip with uppercase names → `open("stop_times.txt")` not null; `open("shapes.txt")` null.
  - `ImportServiceIT` (data dir = temp folder): mini zip → report `imported.size == 1`, feed READY, dates 2026-01-01..2026-01-31; second `importAll()` → `skipped == 1`, feed count 1, stop_time count still 7; add `broken.zip` (random bytes) → that file in `failed`, mini still READY; zip missing `stops.txt` → `FAILED` row, no `agency` rows left for that feed id; `FeedImported` received once (`@RecordApplicationEvents`).
  - `AdminImportControllerIT`: with property `true` → POST returns 200; with default `false` → 404.
- [ ] **Step 2:** Run `mvn -q test -Dtest='ZipGtfsSourceTest,ImportServiceIT,AdminImportControllerIT'` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS; `ModularityTests` → PASS.
- [ ] **Step 5:** Commit `feat(importer): idempotent zip import with COPY, startup and admin triggers`.

### Task 8: Departures

**Files:**
- Create: `schedule/ServiceCalendar.kt`, `schedule/DepartureService.kt`, `schedule/DepartureController.kt`, extend `schedule/ScheduleRepository.kt`
- Test: `schedule/ServiceCalendarIT.kt`, `schedule/DepartureControllerIT.kt`

**Interfaces:**
- Consumes: `FeedLookup` (`findByDate`, `current`, `findById`), `NetworkLookup` (`stopExists`, `platformsOf`, `routes`, `stopNames`).
- Produces:
  - `ServiceCalendar.activeServices(feedId: Int, date: LocalDate): Set<String>` – calendar rows in range with weekday flag, plus `calendar_date` type 1, minus type 2.
  - REST `GET /api/v1/stops/{stopId}/departures` and `GET /api/v1/stations/{stationId}/departures`, params `date` (ISO, optional), `from` (`HH:MM`, default `00:00`), `limit` (default 20, max 200), `feedId` (optional int).
  - Response `DeparturesDto(serviceDate: LocalDate, feedId: Int, fallback: Boolean, departures: List<DepartureDto>)`; `DepartureDto(tripId, routeId, routeShortName, routeColor, routeType, headsign, stopId, stopName, time: String /* HH:MM:SS, may be ≥24 */, timestamp: OffsetDateTime, serviceDate: LocalDate)`. Fallback header as in Task 3.

Algorithm:
- Target date D: `date` if given. If absent: `current()`; if not fallback, D = today; if fallback, D = latest date ≤ fallback feed `end_date` and ≥ `start_date` with today's weekday (none → 404).
- For service day S in [D-1, D]: feed F_S = `feedId` if given (must cover S, else skip that day; if it covers neither → 404), else `findByDate(S)` (null → skip). Explicit `date` with no feed for D → 404 problem `No timetable covers <D>`.
- Stop IDs for F_S: stop path → `[stopId]` if `stopExists`, station path → `platformsOf` (unknown → skip). If neither day resolves the stop/station → 404.
- Window: S = D → `departure_secs >= fromSecs`; S = D-1 → `departure_secs >= 86400 + fromSecs`. Exclude a trip's last stop (no departure). Merge both days, sort by timestamp, take `limit`.
- Headsign = `trip.headsign ?: trip.derived_headsign`.

- [ ] **Step 1:** Write failing tests (fixture imported, `Clock` fixed at 2026-10-26T10:00 Europe/Berlin):
  - `ServiceCalendarIT`: 2026-01-05 (Mon) → `{1}`; 2026-01-06 → `{2}`; 2026-01-10 (Sat) → `{2}`; 2026-02-01 → `{}`.
  - `DepartureControllerIT`:
    - `/stations/adelenstrasse/departures?date=2026-01-08` → trips `T3` (time `24:30:00`, timestamp `2026-01-08T00:30:00+01:00`, serviceDate 2026-01-07, stop 101), `T1` (08:00, stop 101), `T2` (09:00, stop 102), in that order; T1 headsign `Westfalenhallen`.
    - `/stops/101/departures?date=2026-01-08&from=07:00` → only `T1`.
    - `/stops/101/departures?date=2026-01-06` → `T3` from Jan 5 only (svc 1 removed on Jan 6), no `T1`.
    - `/stops/300/departures?date=2026-01-08` → empty list (300 is the last stop of every trip).
    - `/stops/101/departures?date=2026-02-01` → 404 (no feed covers D, even though D-1 is covered).
    - `/stops/101/departures?date=2026-03-15` → 404 problem+json.
    - `/stops/101/departures` (no date) → `fallback == true`, header set, `serviceDate == 2026-01-26` (last Monday in range).
    - `/stops/101/departures?date=2026-13-01` and `?from=25:99` → 400; `?limit=201` → 400.
    - `/stops/101/departures?date=2026-01-08&feedId=<id>` → same as without `feedId`.
- [ ] **Step 2:** Run `mvn -q test -Dtest='ServiceCalendarIT,DepartureControllerIT'` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS; full `mvn -q verify` → PASS.
- [ ] **Step 5:** Commit `feat(schedule): stop and station departures`.

### Task 9: Real-data verification and README

**Files:**
- Create: `README.md` (how to run: `mvn spring-boot:run -Dspring-boot.run.profiles=local`, config keys, endpoint list, Swagger UI at `/swagger-ui.html`)

- [ ] **Step 1:** Run `mvn spring-boot:run -Dspring-boot.run.profiles=local` with `oscdb.import.data-dir=/Users/phil/Entwicklung/MOVE.digital/Daten`. Expected log: 19 feeds imported, 0 failed (incl. uppercase `2024/210824_311024_gtfs_dsw21.zip`). Note total import time.
- [ ] **Step 2:** `curl localhost:8080/api/v1/feeds?size=50` → 19 READY feeds; `curl -i localhost:8080/api/v1/feeds/current` → `OSCDB-Feed-Fallback: true`, end date 2026-08-31.
- [ ] **Step 3:** `curl 'localhost:8080/api/v1/feeds/current/stations?q=adelen'` → contains `adelenstrasse`; `curl 'localhost:8080/api/v1/stations/adelenstrasse/departures?date=2026-07-01&from=07:00'` → non-empty, route `U4x`/bus names, headsigns set. Response time < 1 s.
- [ ] **Step 4:** Restart the app → log shows 19 skipped, 0 imported.
- [ ] **Step 5:** Write README; commit `docs: README with run instructions and endpoints`.
