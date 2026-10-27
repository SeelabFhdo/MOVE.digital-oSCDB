# Natural-language Inquiries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Programs submit a plain-language question plus `format` (`json|csv|xml|geojson`); a local Ollama model turns it into SQL over curated GTFS-safe views; a background worker runs it read-only and the result is polled and downloaded.

**Architecture:** New Modulith module `inquiry` (job table, curated views in schema `inquiry_api`, restricted DB role `oscdb_inquiry` on its own Hikari pool, Ollama client, single worker, REST controller). `schedule` gains a materialized `service_day` table so calendar logic never reaches the model.

**Tech Stack:** Kotlin, Spring Boot 4.1.2, Spring Modulith 2.1.2, PostgreSQL 17, Flyway, `JdbcClient`, Jackson 3 (`tools.jackson`), FastCSV 4.4.0, JDK `XMLStreamWriter`, JDK `HttpServer` (tests only), Ollama HTTP API via `RestClient`.

**Spec:** `docs/superpowers/specs/2026-10-27-natural-language-inquiries-design.md`

## Global Constraints

- Module root `de.movedigital.oscdb.inquiry`, implementation in `inquiry.internal`, marker `src/main/java/de/movedigital/oscdb/inquiry/package-info.java` (`@ApplicationModule`). `ModularityTests` must stay green.
- REST prefix `/api/v1/inquiries`; errors RFC 9457 problem+json via `shared.web.ProblemHandling`.
- Formats exactly `json`, `csv`, `xml`, `geojson` (case-insensitive input); content types `application/json`, `text/csv`, `application/xml`, `application/geo+json`.
- Status values exactly `QUEUED | RUNNING | DONE | FAILED`.
- Max rows 10 000 (truncation detected with `LIMIT max+1`), statement timeout 30 s, model timeout 120 s, retention 24 h, question max 2 000 chars — all in `oscdb.inquiry.*`.
- Result headers `OSCDB-Truncated: true|false`, `OSCDB-Row-Count: <n>`.
- Generated SQL runs only as role `oscdb_inquiry` (SELECT on `inquiry_api` only), in a read-only transaction that is always rolled back.
- Default model `qwen3.8-27b-32k:latest`, Ollama `http://localhost:11434`.
- Tests never call a real model.
- Times: Europe/Berlin; GTFS timestamp = service-day noon − 12 h + seconds.

## Findings that change the spec (rulings)

- **Ollama JSON mode is unavailable** for both installed models (`{"error":"structured output is unavailable"}`, verified 2026-10-27). Ruling: prompt-only JSON (spec's fallback); the parser strips ```` ``` ```` fences and takes the outermost `{…}`; unparsable output counts as a failed attempt and goes through the repair retry. Send `"think": false`, `"options": {"temperature": 0, "num_ctx": 16384}`. Measured ~8–11 s per call.
- **`effective` is computed, not stored**: view `v_feed_day` picks the winning READY feed per date (newest `imported_at`, then highest id); `v_service_day.effective = coalesce(winner = feed_id, true)`. Same behavior as the spec's recomputed column, no stale state, no cross-feed update after import.
- **`v_feed` has no `is_current`**: the DB clock and the app `Clock` differ (tests use a fixed clock); the prompt states the current feed from `FeedLookup.current()` instead.
- **Route type labels** reuse the app's existing labels (`tram, subway, rail, bus, monorail, other`), not German names.
- **Role password** is set at startup (`alter role … password`) by the app, not via a Flyway placeholder, so changing the password needs no new migration.

## Review Focus

1. A question whose SQL references a base table (`stop_time`) or tries `UPDATE`/`DROP`/`set_config` → job FAILED, nothing changes, the next job still has its timeout. (Task 3 tests.)
2. Overlapping feeds → normal departure questions count each stop event once. (Task 2 test.)
3. Model down / Ollama not running → job FAILED with "Model unavailable", worker keeps running. (Task 6 test.)
4. App restarted mid-job → job back to QUEUED, then processed. (Task 6 test.)
5. Column names that are not valid XML element names (`count(*)`, `1st`) or duplicated → still valid XML/JSON. (Task 4 test.)

---

### Task 1: `service_day` in `schedule`

**Files:**
- Create: `src/main/resources/db/migration/V4__service_day.sql`
- Modify: `src/main/kotlin/de/movedigital/oscdb/schedule/internal/JdbcScheduleLoader.kt` (after `loadCalendarDates`)
- Test: `src/test/kotlin/de/movedigital/oscdb/schedule/ServiceDayIT.kt`

**Interfaces:**
- Produces: table `service_day(feed_id int, service_id text, service_date date)`, PK `(feed_id, service_id, service_date)`, index `service_day_date_idx (service_date)`, FK feed `on delete cascade`; SQL function `fill_service_days(p_feed int) returns void`.

- [ ] **Step 1: Test** `ServiceDayIT` (style of `DepartureControllerIT`: truncate, register, load mini in a `TransactionTemplate`):
  - `service 1 runs on 21 days and not on 2026-01-06`: count for service `1` = 21; no row for `2026-01-06`.
  - `service 2 runs on weekends plus 2026-01-06`: count = 10; row for `2026-01-06` exists.
  - `fill_service_days is idempotent per feed after delete`: delete feed → no `service_day` rows remain.
- [ ] **Step 2:** Run `mvn test -Dtest=ServiceDayIT` → FAIL (table missing).
- [ ] **Step 3:** Migration: table, index, function, backfill `select fill_service_days(id) from feed;`. Function body:

```sql
insert into service_day (feed_id, service_id, service_date)
select c.feed_id, c.service_id, d::date
from calendar c cross join lateral generate_series(c.start_date, c.end_date, interval '1 day') d
where c.feed_id = p_feed
  and case extract(isodow from d)
        when 1 then c.monday when 2 then c.tuesday when 3 then c.wednesday when 4 then c.thursday
        when 5 then c.friday when 6 then c.saturday else c.sunday end
union
select feed_id, service_id, date from calendar_date where feed_id = p_feed and exception_type = 1
except
select feed_id, service_id, date from calendar_date where feed_id = p_feed and exception_type = 2;
```

  Loader: `connection.prepareStatement("select fill_service_days(?)")` after `loadCalendarDates`.
- [ ] **Step 4:** `mvn test -Dtest='ServiceDayIT,ScheduleLoaderIT,ImportServiceIT'` → PASS.
- [ ] **Step 5:** Commit `feat(schedule): materialize service days per feed`.

### Task 2: `inquiry` module skeleton, role and curated views

**Files:**
- Create: `src/main/java/de/movedigital/oscdb/inquiry/package-info.java`
- Create: `src/main/resources/db/migration/V5__inquiry.sql`
- Create: `src/main/kotlin/de/movedigital/oscdb/inquiry/internal/InquiryProperties.kt`
- Modify: `src/main/resources/application.yml`, `src/test/resources/config/application.yml`
- Test: `src/test/kotlin/de/movedigital/oscdb/inquiry/internal/CuratedViewsIT.kt`

**Interfaces:**
- Produces: schema `inquiry_api` with views `v_feed, v_feed_day, v_agency, v_route, v_stop, v_station, v_trip, v_service_day, v_departure`, function `inquiry_api.distance_m(lat1 float8, lon1 float8, lat2 float8, lon2 float8) returns float8` (haversine, R = 6 371 000 m, `immutable`); every view and column has `COMMENT ON`. Table `inquiry` (below). Role `oscdb_inquiry` (LOGIN, no password yet).
- `InquiryProperties` (`@ConfigurationProperties("oscdb.inquiry")`): `workerEnabled: Boolean`, `ollamaUrl: String`, `model: String`, `maxRows: Int`, `statementTimeout: Duration`, `modelTimeout: Duration`, `retention: Duration`, `maxQuestionLength: Int`, `pollInterval: Duration`, `datasource: Datasource(url: String?, username: String, password: String)`. Defaults in `application.yml`: `true`, `http://localhost:11434`, `qwen3.8-27b-32k:latest`, `10000`, `30s`, `120s`, `24h`, `2000`, `1s`, `url` unset, `oscdb_inquiry`/`oscdb_inquiry`. Test config: `worker-enabled: false`.

Table:

```sql
create table inquiry (
    id          uuid primary key,
    seq         bigint generated always as identity,
    question    text not null,
    format      text not null,
    status      text not null check (status in ('QUEUED','RUNNING','DONE','FAILED')),
    created_at  timestamptz not null,
    started_at  timestamptz,
    finished_at timestamptz,
    meta        jsonb,
    error       text,
    result      text
);
create index inquiry_queue_idx on inquiry (status, seq);
create index inquiry_created_idx on inquiry (created_at);
```

Role and grants (end of migration): `do $$ … create role oscdb_inquiry login … $$` only if missing; `grant usage on schema inquiry_api`; `grant select on all tables in schema inquiry_api`; `grant execute on function inquiry_api.distance_m`; `alter role oscdb_inquiry set default_transaction_read_only = on`, `set statement_timeout = '30s'`, `set search_path = inquiry_api`, `set timezone = 'Europe/Berlin'`.

Views — all filter `feed.status = 'READY'`; key definitions:

- `v_feed_day(service_date, feed_id)`: `distinct on (d::date)` over `generate_series(start_date, end_date, '1 day')` of READY feeds, `order by d::date, imported_at desc nulls last, id desc`.
- `v_service_day(feed_id, service_id, service_date, effective)`: `service_day` ⨝ READY feed, left join `v_feed_day`, `effective = coalesce(fd.feed_id = sd.feed_id, true)`.
- `v_departure`: `stop_time` ⨝ `trip` ⨝ `v_service_day` (on feed+service) ⨝ `stop` ⨝ `route`, left join `station`. Columns: `feed_id, service_date, effective, trip_id, stop_sequence, stop_id, stop_name, station_id, station_name, lat, lon, route_id, line, route_type, direction_id, headsign (coalesce(nullif(trip.headsign,''), derived_headsign)), departure_secs, arrival_secs, departure_time, arrival_time ('HH:MM:SS', hours may exceed 24), departure_at, arrival_at (((service_date + time '12:00') at time zone 'Europe/Berlin') - interval '12 hours' + make_interval(secs => secs)), is_first_stop, is_last_stop (correlated min/max(stop_sequence) subqueries on the PK), pickup_type, drop_off_type`.
- `v_route(feed_id, route_id, line, long_name, route_type, route_type_code, color, agency_id, agency_name)`; `v_stop(feed_id, stop_id, name, code, lat, lon, station_id, station_name, wheelchair_boarding)`; `v_station(feed_id, station_id, name, lat, lon, platform_count)`; `v_trip(feed_id, trip_id, route_id, line, route_type, headsign, direction_id, service_id)`; `v_feed(feed_id, source_file, start_date, end_date, version, imported_at)`; `v_agency(feed_id, agency_id, name, url, timezone)`.
- `route_type` label: `case type when 0 then 'tram' when 1 then 'subway' when 2 then 'rail' when 3 then 'bus' when 405 then 'monorail' else 'other' end`.
- Comments must say: ids unique only per `feed_id`; filter `effective = true` unless comparing feeds or a feed is named; `service_date` is the GTFS service day (times past midnight belong to the previous day); use `departure_at` for real clock time; `distance_m` returns metres.

- [ ] **Step 1: Test** `CuratedViewsIT` (mini feed loaded as in Task 1, using main `JdbcClient`):
  - `departures on 2026-01-08`: `select count(*) from inquiry_api.v_departure where service_date='2026-01-08' and effective` = 7.
  - `no departures on 2026-01-06` = 0.
  - `after-midnight trip keeps its service date`: T3 at stop `101` on `2026-01-08`: `departure_time = '24:30:00'`, `departure_at = 2026-01-09T00:30+01:00`, `is_first_stop = true`, `headsign = 'Westfalenhallen'`.
  - `overlapping feed wins and nothing is double counted`: load mini as a second READY feed → effective count on 2026-01-08 still 7, all with the second feed id; total (non-filtered) count 14.
  - `route type label`: `v_route` line `U41` → `subway`, `440` → `bus`.
  - `distance_m`: Adelenstraße 101 → 102 between 10 and 30 m.
  - `every view and column has a comment`: query `pg_catalog` for `inquiry_api` columns with null `col_description` → empty.
- [ ] **Step 2:** `mvn test -Dtest=CuratedViewsIT` → FAIL.
- [ ] **Step 3:** Write migration, `package-info.java` (`/** inquiry module. Reads other modules' tables only through the SQL views in schema inquiry_api (V5__inquiry.sql). */`), properties, yml.
- [ ] **Step 4:** `mvn test -Dtest='CuratedViewsIT,ModularityTests'` → PASS.
- [ ] **Step 5:** Commit `feat(inquiry): curated GTFS views, inquiry table and restricted role`.

### Task 3: Restricted execution

**Files:**
- Create: `inquiry/internal/InquiryDatabase.kt`, `inquiry/internal/SqlGuard.kt`, `inquiry/internal/QueryExecutor.kt`, `inquiry/internal/ResultTable.kt`
- Test: `src/test/kotlin/de/movedigital/oscdb/inquiry/internal/SqlGuardTest.kt`, `QueryExecutorIT.kt`

**Interfaces:**
- `InquiryDatabase` (`@Component`, NOT a `DataSource` bean — a second `DataSource` bean would switch off Boot's auto-configured one): lazily builds a `HikariDataSource` (pool `inquiry`, max 2) with `datasource.url ?: mainDataSource.unwrap(HikariDataSource::class.java).jdbcUrl`; before first use runs `alter role oscdb_inquiry password <quote_literal(password)>` through the main `JdbcClient` (on failure: log warn, continue); `fun <T> withConnection(block: (Connection) -> T): T`; closes the pool on shutdown.
- `object SqlGuard { fun check(sql: String): String }` — trims, drops trailing `;`s, throws `InvalidSqlException(message)` if blank, if any `;` remains, or if it does not match `^(select|with)\b` (case-insensitive). Returns the cleaned SQL.
- `data class ResultTable(val columns: List<String>, val rows: List<List<Any?>>, val truncated: Boolean)`; values normalized to `String | Number | Boolean | null` (`date`→ISO date, `timestamptz`→ISO offset in Europe/Berlin, `timestamp`/`time`→ISO, other non-primitive → `toString()`); duplicate column names get `_2`, `_3`.
- `QueryExecutor.run(sql: String): ResultTable` — on `InquiryDatabase`: `autoCommit=false`, `readOnly=true`, `set transaction read only`, `set local statement_timeout = '<ms>ms'`, `set local search_path = inquiry_api`, `set local timezone = 'Europe/Berlin'`, `explain <sql>`, then `select * from (<sql>\n) as q limit <maxRows+1>`; always `rollback`. SQL errors propagate as `SQLException`.

- [ ] **Step 1: Tests.** `SqlGuardTest`: `select 1;` → `select 1`; `WITH x as (select 1) select * from x` ok; `delete from v_stop` → throws; `select 1; drop table feed` → throws; empty → throws. `QueryExecutorIT` (mini loaded, `oscdb.inquiry.max-rows=3`, `oscdb.inquiry.statement-timeout=1s` via `@SpringBootTest(properties=…)`):
  - `selects from views without schema prefix`: `select stop_id from v_stop order by stop_id` → 3 rows, `truncated = true`.
  - `base tables are not readable`: `select * from public.stop_time` → `SQLException` containing `permission denied`.
  - `writes are rejected` (bypass the guard, use `InquiryDatabase.withConnection` as the role): `create table x(i int)` → `SQLException`; `update inquiry_api.v_stop set name = 'x'` → `SQLException`; `select * from v_stop for update` through `QueryExecutor.run` → `SQLException`. `select distance_m(0,0,0,0)` through the executor works.
  - `timeout cannot be disabled`: run `select set_config('statement_timeout','0',false)`, then `select pg_sleep(3)` → `SQLException` (canceling statement due to statement timeout).
  - `timestamptz is rendered in Berlin time`: `departure_at` for T3 = `"2026-01-09T00:30+01:00"`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** `mvn test -Dtest='SqlGuardTest,QueryExecutorIT'` → PASS.
- [ ] **Step 5:** Commit `feat(inquiry): run generated SQL as a read-only restricted role`.

### Task 4: Renderers

**Files:**
- Create: `inquiry/OutputFormat.kt` (public, used by API), `inquiry/internal/ResultRenderer.kt`, `inquiry/internal/InquiryMeta.kt`
- Test: `src/test/kotlin/de/movedigital/oscdb/inquiry/internal/ResultRendererTest.kt`

**Interfaces:**
- `enum class OutputFormat(val wire: String, val contentType: String) { JSON, CSV, XML, GEOJSON; companion fun parse(value: String?): OutputFormat? }`.
- `data class InquiryMeta(val sql: String?, val previousSql: String?, val assumptions: List<String>, val feeds: List<Int>, val rowCount: Int?, val truncated: Boolean?, val model: String, val modelMillis: Long, val queryMillis: Long?, val attempts: Int)`.
- `class ResultRenderer(json: JsonMapper)`: `fun render(table: ResultTable, format: OutputFormat, meta: InquiryMeta): String`; `fun hasCoordinates(table: ResultTable): Boolean` (columns `lat` and `lon`, case-insensitive).
- JSON `{"meta":…,"rows":[{col:value}]}`; CSV header + rows, null → empty (FastCSV); XML `<result><meta><sql/><rowCount/><truncated/><assumptions><assumption/></assumptions></meta><rows><row><col>…</col></row></rows></result>`, element names sanitized to `[A-Za-z0-9_]`, `_` prefix when not starting with a letter/`_`, null → empty element with `nil="true"`; GeoJSON `FeatureCollection` with `meta` member, Point `[lon, lat]`, other columns as properties, null coordinates → `geometry: null`.

- [ ] **Step 1: Tests:** one per format with a 2-row table `(line, count(*), lat, lon)`; XML element for `count(*)` is `count___`… (assert sanitized name `count___` exists and document parses with `DocumentBuilder`); CSV value with comma and quote is quoted; duplicate columns already de-duplicated upstream (assert JSON keys distinct for `name, name_2`); GeoJSON coordinates order `[lon, lat]`.
- [ ] **Step 2–4:** fail → implement → `mvn test -Dtest=ResultRendererTest` PASS.
- [ ] **Step 5:** Commit `feat(inquiry): render results as json, csv, xml and geojson`.

### Task 5: Model client

**Files:**
- Create: `inquiry/internal/SqlTranslator.kt` (interface + types), `inquiry/internal/PromptBuilder.kt`, `inquiry/internal/OllamaSqlTranslator.kt`, `src/main/resources/inquiry/system-prompt.md`
- Test: `OllamaSqlTranslatorTest.kt` (JDK `HttpServer` on port 0), `PromptBuilderIT.kt`

**Interfaces:**
- `interface SqlTranslator { val model: String; fun translate(question: String, format: OutputFormat, retry: RetryContext?): Translation }`
- `data class RetryContext(val previousAnswer: String, val error: String)`
- `data class Translation(val answerable: Boolean, val reason: String?, val sql: String?, val assumptions: List<String>, val feedIds: List<Int>, val raw: String, val millis: Long)`
- `class ModelUnavailableException(msg, cause)`; `class InvalidModelOutputException(val raw: String, msg)`.
- `PromptBuilder.systemPrompt(format: OutputFormat): String` — template placeholders `{{schema}}` (generated once from `pg_catalog`: each view `name — comment` then `column type — comment`, plus `distance_m`), `{{today}}` (app `Clock`), `{{currentFeed}}` (`FeedLookup.current()`: id, dates, `fallback`), `{{feeds}}` (all `v_feed` rows), `{{format}}` (for `geojson`: "include lat and lon columns").
- Prompt rules (verbatim intent): only objects of `inquiry_api`; exactly one `SELECT`/`WITH`; no `LIMIT` unless the question asks for top N; always filter `feed_id` (current feed unless the question names a period/date) and `effective = true` unless comparing feeds; snake_case column names reused from the views; dates as `date 'YYYY-MM-DD'`; if no reasonable interpretation exists set `answerable=false` with `reason`; list every assumption (feed, date, interpretation); reply with only one JSON object `{"answerable":bool,"reason":string|null,"sql":string,"assumptions":[string],"feedIds":[int]}`. Include 4 worked examples (departures at a station on a date, trips per line on a Saturday, compare stop counts between two feeds, stops within 500 m of a point).
- Ollama request: `POST {ollamaUrl}/api/chat` `{model, stream:false, think:false, options:{temperature:0, num_ctx:16384}, messages:[system, user(question) (, assistant(previousAnswer), user("That answer failed: <error>. Reply with a corrected JSON object."))]}`; read timeout `modelTimeout` (`JdkClientHttpRequestFactory`); response `message.content`. Parse: strip code fences, take substring from first `{` to last `}`, Jackson `JsonMapper`. Connect/IO errors and non-2xx → `ModelUnavailableException`.

- [ ] **Step 1: Tests:** stub server returns `{"message":{"content":"```json\n{\"answerable\":true,\"sql\":\"select 1\",\"assumptions\":[\"a\"],\"feedIds\":[19]}\n```"}}` → parsed; request body has `stream=false`, `think=false`, the model name, 2 messages; with `RetryContext` 4 messages and the error text in the last; content `"no json here"` → `InvalidModelOutputException`; server returns 500 → `ModelUnavailableException`; closed port → `ModelUnavailableException`. `PromptBuilderIT`: prompt contains `v_departure`, `departure_at`, `distance_m`, `2026-10-26`, the loaded feed id, and the geojson hint only for `GEOJSON`.
- [ ] **Step 2–4:** fail → implement → `mvn test -Dtest='OllamaSqlTranslatorTest,PromptBuilderIT'` PASS.
- [ ] **Step 5:** Commit `feat(inquiry): Ollama SQL translator with generated schema prompt`.

### Task 6: Jobs, processing, worker, retention

**Files:**
- Create: `inquiry/InquiryStatus.kt`, `inquiry/internal/InquiryRepository.kt`, `inquiry/internal/InquiryProcessor.kt`, `inquiry/internal/InquiryWorker.kt`, `inquiry/internal/InquirySchedulingConfig.kt`
- Test: `src/test/kotlin/de/movedigital/oscdb/inquiry/internal/InquiryProcessorIT.kt`, test helper `ScriptedTranslator.kt` (`@TestConfiguration`, `@Primary` `SqlTranslator` bean answering from a queue of lambdas, recording calls)

**Interfaces:**
- `enum class InquiryStatus { QUEUED, RUNNING, DONE, FAILED }`
- `data class Inquiry(id: UUID, question, format: OutputFormat, status, createdAt: Instant, startedAt: Instant?, finishedAt: Instant?, meta: InquiryMeta?, error: String?, queuePosition: Int?)`
- `InquiryRepository`: `create(question, format): Inquiry` (id `UUID.randomUUID()`, `created_at = clock.instant()`); `find(id): Inquiry?` (null if older than `retention`); `result(id): StoredResult?` (`data class StoredResult(status, format, body: String?, meta: InquiryMeta?, error: String?)`); `claimNext(): Inquiry?` (`update … status='RUNNING', started_at=:now where id = (select id from inquiry where status='QUEUED' order by seq limit 1 for update skip locked) returning *`); `complete(id, meta, body)`; `fail(id, error, meta?)`; `requeueRunning(): Int`; `deleteExpired(): Int`.
- `InquiryProcessor.process(job: Inquiry)` — algorithm:

```
attempt 1..2:
  translation = translator.translate(q, fmt, retry)      // ModelUnavailable → FAIL "Model unavailable: …" (no retry)
                                                         // InvalidModelOutput → retry = (raw, msg); continue
  if !answerable → FAIL "Not answerable: <reason>"
  sql = SqlGuard.check(translation.sql)                  // InvalidSql → retry
  table = executor.run(sql)                              // SQLException → retry with its message
  if fmt == GEOJSON && !hasCoordinates → retry "geojson needs lat and lon columns"
  meta = …feeds = (feedIds ∪ distinct feed_id column values).sorted()
  complete(render) ; return
after attempt 2 → FAIL "Query failed: <last error>" with meta (sql, previousSql, attempts=2)
```

  Any unexpected exception → FAIL "Internal error" (logged), never leaves `RUNNING`.
- `InquiryWorker`: `fun runNext(): Boolean` (claim + process); `@Scheduled(fixedDelayString = "\${oscdb.inquiry.poll-interval}") fun poll()` loops `runNext()` until false; `@Scheduled(cron = "0 0 * * * *") fun cleanup()`; `@EventListener(ApplicationReadyEvent::class) fun recover()` → `requeueRunning()`.
- `InquirySchedulingConfig`: `@Configuration @EnableScheduling @ConditionalOnProperty("oscdb.inquiry.worker-enabled", havingValue = "true")`.

- [ ] **Step 1: Tests** (`InquiryProcessorIT`, mini loaded, `ScriptedTranslator`):
  - `success stores rendered csv and meta`: answer `select line from v_route where feed_id=<id> order by line` → DONE, body `line\n440\nU41\n` (line endings per FastCSV), `meta.rowCount=2`, `truncated=false`, `attempts=1`, `feeds=[id]`.
  - `sql error is repaired once`: first answer `select nope from v_stop`, second valid → DONE, `attempts=2`, `previousSql` set, second call got `RetryContext` whose error contains `nope`.
  - `two failures end FAILED`: both invalid → FAILED, error starts `Query failed:`.
  - `base table access fails`: `select * from stop_time` twice → FAILED (permission denied / relation does not exist).
  - `not answerable`: `answerable=false, reason="no realtime data"` → FAILED `Not answerable: no realtime data`, 1 call.
  - `model unavailable`: translator throws `ModelUnavailableException` → FAILED `Model unavailable: …`.
  - `geojson without coordinates is retried`: first `select line from v_route…`, second with `lat, lon` from `v_stop` → DONE.
  - `queue order`: create A, B → `claimNext()` returns A then B then null.
  - `recover requeues running jobs`: set a row `RUNNING` → `recover()` → `QUEUED`.
  - `expired jobs vanish`: set `created_at` = fixed clock − 25 h → `find` null; `deleteExpired()` = 1.
- [ ] **Step 2–4:** fail → implement → `mvn test -Dtest=InquiryProcessorIT` PASS.
- [ ] **Step 5:** Commit `feat(inquiry): job queue, processing with repair retry, worker and retention`.

### Task 7: REST API

**Files:**
- Create: `inquiry/internal/InquiryController.kt`
- Modify: `shared/web/ProblemHandling.kt` (+ `class ConflictException(val detail: String)` → 409)
- Test: `src/test/kotlin/de/movedigital/oscdb/inquiry/InquiryControllerIT.kt`

**Interfaces:**
- `POST /api/v1/inquiries` body `{"question": string, "format": string}` → `202`, `Location: /api/v1/inquiries/{id}`, body = job view. 400 problem when question blank / longer than `maxQuestionLength` / format not one of the four (`detail` names the allowed values).
- `GET /api/v1/inquiries/{id}` → `{id, question, format, status, createdAt, startedAt, finishedAt, queuePosition, error, meta}` (nulls omitted or null — follow existing DTO style); unknown/expired/malformed id → 404 `Unknown inquiry '<id>'`.
- `GET /api/v1/inquiries/{id}/result` → `200` with the stored body, `Content-Type` from format (`charset=UTF-8` for text types), headers `OSCDB-Truncated`, `OSCDB-Row-Count`; `QUEUED`/`RUNNING` → 409 `Inquiry <id> is <status>`; `FAILED` → 409 `Inquiry <id> failed: <error>`; unknown → 404.

- [ ] **Step 1: Tests:** submit → 202 + Location + `status=QUEUED`, `queuePosition=1`; bad format `pdf` → 400 problem+json; blank question → 400; 2 001-char question → 400; `GET` random UUID → 404; `GET /api/v1/inquiries/not-a-uuid` → 404; result while queued → 409; after `worker.runNext()` with scripted answer → status DONE, result `text/csv` with headers `OSCDB-Truncated: false`, `OSCDB-Row-Count: 2`; FAILED job result → 409 with error text; geojson result content type `application/geo+json`.
- [ ] **Step 2–4:** fail → implement → `mvn test -Dtest=InquiryControllerIT` PASS; then full `mvn test` PASS.
- [ ] **Step 5:** Commit `feat(inquiry): REST endpoints for submitting and polling inquiries`.

### Task 8: Documentation and real-model verification

**Files:**
- Create: `docs/inquiries.md` (usage guide), `scripts/inquiry-eval.sh`, `scripts/inquiry-eval-questions.txt`
- Modify: `README.md` (short section + link + config keys), spec "Accepted deviations" section (the five rulings above)

- [ ] **Step 1:** `docs/inquiries.md`: prerequisites (Ollama running, model pulled, `ollama pull`/model config), the three endpoints with curl examples for each format, polling loop example (bash), job JSON and `meta` fields, status lifecycle, error cases (400/404/409, FAILED reasons), assumptions and how to steer them (name the feed/date in the question), limits (10 000 rows, 30 s, 24 h, one job at a time, ~10–60 s per job), the view catalogue (one line per view), configuration table, security model (role, read-only), troubleshooting (model unavailable, timeout, wrong answers → read `meta.sql`).
- [ ] **Step 2:** `inquiry-eval.sh`: reads questions (`format|question` per line) from the txt, submits each to `${BASE:-http://localhost:8080}`, polls until done, prints status, rowCount, assumptions, sql, and the first 5 lines of the result. ~15 questions over the four kinds.
- [ ] **Step 3:** Run the app (`local` profile) against the real data and Ollama, run the eval script, fix prompt/view problems found, record the outcome (pass/fail per question, timings) in `docs/inquiries.md` "Evaluation" section.
- [ ] **Step 4:** Full `mvn test` PASS.
- [ ] **Step 5:** Commit `docs(inquiry): usage guide, eval script and results`.
