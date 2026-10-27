# OSCDB – Natural-language inquiries (design)

Date: 2026-10-27
Status: implemented (2026-10-27)
Builds on: `2026-10-26-gtfs-rest-api-design.md`

## Intent

Programs that consume OSCDB should be able to ask for data in plain language
("top 10 busiest stops on a Saturday in the 2026 timetable") and name the file
format they want the answer in, instead of being limited to the fixed REST
endpoints. The fixed endpoints only cover lookups; this feature opens up
statistics, timetable comparisons and location questions without writing a
new endpoint for each.

## Decided by the user

- Question kinds to support: simple lookups, statistics, timetable
  comparisons (across feeds), location-based questions.
- `format` is one of a fixed set: `json`, `csv`, `xml`, `geojson`. The app
  decides the structure inside the format.
- Main callers are other programs, which rely on the result.
- Every call asks the AI afresh (no saved/named queries).
- AI runs locally (Ollama on this machine), nothing leaves the machine.
- Ambiguous requests: answer the most likely interpretation and report the
  assumptions made.
- Asynchronous: submit, get a job id, poll for status/result.
- Completion is learned by polling (no webhooks).
- Results capped (10 000 rows), flagged when truncated.
- Finished jobs and results are kept 24 h, then deleted.
- Approach C (below): the AI writes SQL against curated views.

## Goals

- `POST` a question + format, get a job id back immediately.
- Poll the job; when done, download the result in the requested format.
- Every result says how it was produced: generated SQL, assumptions, feed(s)
  and dates used, row count, truncation, model name.
- Generated SQL can never change data or read outside the curated views.
- Correct GTFS semantics (service calendar, exceptions, after-midnight trips,
  per-feed ids) without the model having to know them.

## Non-goals

- No saved/named queries, no conversational follow-ups.
- No cloud AI providers in this version.
- No free-form output shapes (format describes the file type only).
- No webhooks, no streaming of huge exports.
- No authentication (local-only, same as the rest of the app).

## Anti-goals

- The model touching base tables, writing data, or running unbounded queries.
- Plausible-looking but wrong answers because calendar logic was left to the
  model (the reason approach C was chosen).
- Silent assumptions: a guessed date or feed that the caller cannot see.
- A stuck or crashed job that stays `RUNNING` forever.

## Constraints and facts

- Ollama is installed at `/opt/homebrew/bin/ollama`; models present:
  `qwen3.8-27b-32k:latest` and `qwen3.8:27b-mlx` (18 GB each). Machine:
  Apple M5 Max, 36 GB RAM. Expect roughly 5–20 s per model call.
- The local model handles one generation at a time.
- Postgres 17 already holds ~25 M `stop_time` rows across 19 feeds; ids are
  only unique per feed (`(feed_id, id)` keys).
- No PostGIS; coordinates are `lat`/`lon` double columns.
- Existing app conventions apply: Spring Modulith modules with
  `<module>.internal`, Flyway, `JdbcClient`, RFC 9457 problem+json,
  `/api/v1` prefix, localhost binding.

## Approaches considered

| | Approach | Trade-off |
|-|-|-|
| A | Model writes SQL against the raw tables | Most flexible, but a 27B local model often gets calendar/exception logic, >24 h times and `(feed_id, id)` joins wrong. |
| B | Model only picks from fixed functions and fills parameters | Very reliable, but every new question kind needs new code — contradicts "ask anything". |
| **C (chosen)** | Model writes SQL against curated, GTFS-safe views; app validates and runs it read-only, one repair retry | Nearly as flexible as A, far more accurate; the views become a stable contract. |

## Design

### Module

New Spring Modulith module `inquiry` (`de.movedigital.oscdb.inquiry`). It
depends on `feed` (for `current` resolution in prompts/assumptions) and owns:
the job table, the curated views, the model client, the worker, the REST
controller.

*(builder's call)* The curated views read tables owned by `feed`, `network`
and `schedule`. This is a deliberate, documented exception to "modules only
talk through APIs": the views are a read-only reporting projection, owned by
`inquiry`'s migrations, and are the only place that coupling exists. Base
table changes in other modules must keep the views compiling (a test covers
this). The coupling is SQL-only, so Modulith does not see it; no
`@NamedInterface` or verification exception is needed, and the module's
`package-info` documents it. *(builder's call)*

### Service-day materialization (owned by `schedule`)

Expanding calendars at query time is what makes raw SQL error-prone, so it
is done once at import:

- New table `service_day(feed_id, service_id, service_date, effective bool)`:
  one row per date a service runs (calendar weekday flags within range, plus
  `calendar_dates` type 1, minus type 2). Filled by `schedule` during feed
  import.
- `effective` = this feed is the one the app resolves for that date
  (existing rule: among READY feeds covering the date, newest `imported_at`
  wins). Recomputed for all feeds whenever a feed becomes READY, so overlaps
  never double-count.
- Overlaps are absent in today's data but allowed by the import, so
  `effective` must stay correct: after a feed becomes READY, recompute
  `effective` for the dates that feed covers (all feeds touching those
  dates). *(builder's call)*
- Existing feeds get backfilled by the migration or on startup
  *(builder's call which)*.

### Curated views (schema `inquiry_api`)

Exact columns are the builder's call; the set and their meaning are fixed.
Every view carries `feed_id`.

*(builder's call)* Date-based views (`v_service_day`, `v_departure`) cover
**all** feeds and expose an `effective` column instead of hiding rows. The
prompt rule: filter `effective = true` unless the question names a feed or
compares timetable periods, in which case filter by `feed_id`. This keeps
comparisons possible without double counting normal questions.

*(builder's call)* The builder may add pre-aggregated materialized views
(e.g. trips per route per service day, departures per stop per service day),
refreshed after import, when the eval set shows plain views miss the
statement timeout.

*(builder's call)* View and column documentation lives in Postgres
`COMMENT ON` statements; the prompt's schema section is generated from
`pg_catalog` at startup, so prompt and views cannot drift.

- `v_feed` – feed id, start/end date, version, status, `is_current`.
- `v_agency` – agencies.
- `v_route` – line (short name), long name, type code, `type_label`
  (U-Bahn/Stadtbahn, Bus, H-Bahn), color, agency name.
- `v_station` – station id, name, lat, lon, platform count.
- `v_stop` – stop id, name, code, lat, lon, station id, station name.
- `v_trip` – trip id, line, type label, headsign (GTFS or derived),
  direction, service id.
- `v_service_day` – feed, service id, service date, effective.
- `v_departure` – one row per stop event on an effective service day:
  service date, departure/arrival as `HH:MM:SS` (may exceed 24 h) **and** as
  real `timestamptz` in Europe/Berlin (noon − 12 h + seconds), stop/station
  id and name, lat/lon, line, type label, headsign, trip id, stop sequence,
  `is_first_stop`, `is_last_stop`.
- SQL function `inquiry_api.distance_m(lat1, lon1, lat2, lon2)` (haversine,
  metres) for location questions.

Views over all feeds must stay fast for filtered queries: indexes on
`service_day(service_date)` and `service_day(feed_id, service_id)` plus the
existing `stop_time_stop_dep_idx`.

### Request flow

1. `POST /api/v1/inquiries` with JSON `{ "question": "...", "format":
   "csv" }` → `202 Accepted`, `Location: /api/v1/inquiries/{id}`, body
   `{ id, status: "QUEUED" }`. Invalid/missing format or empty question →
   400 problem+json. Question max length *(builder's call, e.g. 2 000
   chars)*.
2. A single background worker takes the oldest `QUEUED` job
   (`RUNNING`), builds the prompt and calls the model.
3. Prompt contains: view definitions with column comments, today's date,
   the `current` feed (incl. fallback state) and the feed list, rules
   (only `inquiry_api` objects, one `SELECT`/`WITH`, no `LIMIT` needed),
   and a handful of worked examples. Model must answer structured JSON:
   `{ answerable, reason, sql, assumptions[], feedIds[] }`. The prompt
   requires an explicit `feed_id` filter; `feedIds` is what the model says
   it used and goes into `meta.feeds`, cross-checked against a `feed_id`
   result column when present. *(builder's call)*
4. Not answerable (no reasonable interpretation, e.g. realtime delays) →
   job `FAILED` with the model's reason. *(builder's call: user chose
   "best guess"; this only triggers when no guess is possible.)*
5. Validation before running: single statement, starts with
   `SELECT`/`WITH`, then `EXPLAIN` under the restricted role.
6. Execution: separate login role `oscdb_inquiry`, used through a second,
   small `DataSource` with its own credentials (`oscdb.inquiry.datasource.*`;
   no `SET ROLE`, which the generated SQL could undo). Flyway creates the role
   with a password from a Flyway placeholder; locally and in Testcontainers
   the owner is a superuser, so `CREATE ROLE` works. *(builder's call)* The
   role has `USAGE` on schema
   `inquiry_api` and `SELECT` on its views/function only; read-only
   transaction; `statement_timeout` 30 s *(builder's call, configurable)*;
   result capped by wrapping as `SELECT * FROM (<sql>) q LIMIT 10001` to
   detect truncation. The role is the security boundary; text checks are
   only for early, readable errors.
7. If `EXPLAIN` or execution fails with a SQL error, or a `geojson` result
   has no `lat`/`lon` columns, send the error back to
   the model once for a repaired query. Second failure → `FAILED` with the
   error and both SQL attempts in `meta`.
8. Success → render rows into the format once, store the rendered file
   (text column) with its content type, `DONE`. *(builder's call)*

### Job API

- `GET /api/v1/inquiries/{id}` → `{ id, question, format, status, createdAt,
  startedAt, finishedAt, queuePosition?, error?, meta? }`
  - `status`: `QUEUED | RUNNING | DONE | FAILED`
  - `meta`: `sql`, `assumptions[]`, `feeds[]` used (if detectable),
    `rowCount`, `truncated`, `model`, `modelMillis`, `queryMillis`,
    `attempts`.
- `GET /api/v1/inquiries/{id}/result` → the file with the right
  `Content-Type`, plus headers `OSCDB-Truncated: true|false` and
  `OSCDB-Row-Count` so CSV/XML callers see truncation without the job
  endpoint *(builder's call)* (`application/json`, `text/csv`, `application/xml`,
  `application/geo+json`). Not done yet → 409 problem+json. Unknown or
  expired → 404.
- Both 404 after the 24 h retention.

### Formats

- `json`: `{ "meta": {...}, "rows": [ {col: value} ] }`.
- `csv`: header row, RFC 4180, UTF-8; `meta` only via the job endpoint.
- `xml`: `<result><meta>…</meta><rows><row><col>…</col></row></rows></result>`.
- `geojson`: `FeatureCollection`, one Point feature per row from `lat`/`lon`
  columns, other columns as properties. The prompt tells the model to
  include `lat`/`lon` when `geojson` is requested; if the result still has
  no such columns → `FAILED` with a clear reason.
- Column names are whatever the SQL returns; the prompt asks for
  `snake_case` names reused from the views. Decided by the user: column
  names and order are best effort — the same question may return slightly
  different columns on different calls, and callers tolerate that.

### Jobs, queue, retention

- Table `inquiry(id uuid, question, format, status, created_at, started_at,
  finished_at, sql, meta jsonb, error, result bytea/text)`, owned by
  `inquiry`.
- One worker, sequential (the model handles one generation at a time).
- On startup, jobs left `RUNNING` are reset to `QUEUED` (crash recovery).
- Model call timeout *(builder's call, e.g. 120 s)* → `FAILED`.
- Hourly cleanup deletes jobs older than 24 h.

### Configuration (`oscdb.inquiry.*`)

- `enabled` (default `true`; tests switch the worker off or stub the model)
- `ollama-url` (default `http://localhost:11434`)
- `model` (default `qwen3.8-27b-32k:latest`; must support Ollama's JSON
  output mode — the builder verifies this first, falls back to
  `qwen3.8:27b-mlx` or prompt-only JSON parsing otherwise)
- `max-rows` 10000, `statement-timeout` 30s, `model-timeout` 120s,
  `retention` 24h.

Model client: Spring AI's Ollama integration if it supports Boot 4.1,
otherwise a plain `RestClient` against Ollama's `/api/chat` with JSON
output mode *(builder's call)*.

## Testing

- Unit/integration tests never call a real model: a stub translator returns
  canned `{sql, assumptions}` responses.
- Views: integration tests on the existing mini fixture feed (calendar
  exceptions, >24 h trips, overlapping feeds → `effective` and no
  double counting).
- Security: tests that `UPDATE`, `DELETE`, `DROP`, multi-statements, and
  `SELECT` from base tables (`public.stop_time`) are rejected by the role.
- Job lifecycle: queue order, repair retry, failure paths, 409/404, retention
  cleanup, `RUNNING` reset on startup.
- Formats: each of the four renderers, incl. geojson without coordinates.
- Modulith verification still passes.
- **Eval set** (manual, against real Ollama, not in `mvn test`): ~15 example
  questions across the four kinds with expected checks (e.g. row count,
  known value). This is the measure of success for prompt quality.

## Accepted deviations

Recorded during implementation.

- Ollama's JSON output mode is unavailable for both installed models
  (`structured output is unavailable`). The JSON object is requested by the
  prompt and extracted leniently (code fences and `<think>` blocks stripped);
  unparsable answers count as a failed attempt and go through the repair retry.
- `effective` is computed by the view `v_feed_day` (winning feed per date)
  instead of a stored column that is recomputed after each import. Same
  behavior, no stale state.
- `v_feed` has no `is_current` column: the database clock and the app clock
  can differ, so the prompt states the current feed from `FeedLookup.current()`.
- Route type labels reuse the app's existing labels (`tram`, `subway`, `rail`,
  `bus`, `monorail`, `other`).
- The role password is set by the app at startup instead of a Flyway
  placeholder, so a password change needs no migration.
- After the first evaluation, the prompt gained hints about abbreviated names
  (`Hbf`, `Bf`, `DO-`, ` U`/` S` suffixes) and about locating places through
  stop names.

## Left to the builder

- Exact view columns, prompt wording and examples.
- Backfill of `service_day` for existing feeds (migration vs. startup).
- Spring AI vs. `RestClient`.
- Exact question length limit and timeouts.

## Open for later

- Saved/named inquiries, cloud model providers, webhooks, larger exports,
  free-form output shapes, authentication.
