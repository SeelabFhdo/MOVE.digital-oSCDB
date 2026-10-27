# Natural-language inquiries

Ask OSCDB for timetable data in plain language and get the answer as `json`, `csv`, `xml` or `geojson`.
A local language model (Ollama) translates the question into SQL over a set of curated, read-only views.
The app checks that SQL, runs it under a restricted database role, and stores the rendered result for you to download.

Inquiries are **asynchronous**. You submit a question and get a job id back at once. You then poll the job until it is `DONE` or `FAILED`, and download the result. A job usually takes 5–70 s, almost all of it model time.

## Prerequisites

- OSCDB running as described in the [README](../README.md), with feeds imported.
- [Ollama](https://ollama.com) running locally on `http://localhost:11434` with the configured model:

  ```
  ollama list                       # must show qwen3.8-27b-32k:latest (default)
  ollama serve                      # if Ollama is not running yet
  ```

  To use another model, set `oscdb.inquiry.model`. The model needs at least 16k context, and it must answer with a plain JSON object; Ollama's JSON mode is not used.

No data leaves the machine. The model sees your question and the description of the views, not the data itself.

## Quick start

```bash
# 1. Submit
curl -s -X POST localhost:8080/api/v1/inquiries \
  -H 'Content-Type: application/json' \
  -d '{"question": "Top 10 busiest stations by number of departures on 2026-07-01", "format": "csv"}'
# → 202 Accepted, Location: /api/v1/inquiries/3f0c…
# {"id":"3f0c…","status":"QUEUED","queuePosition":1,…}

# 2. Poll until status is DONE or FAILED
curl -s localhost:8080/api/v1/inquiries/3f0c…

# 3. Download the result
curl -s localhost:8080/api/v1/inquiries/3f0c…/result
# station_name,departure_count
# Kampstraße U,1584
# Stadtgarten U,1397
# …
```

A complete submit-and-wait loop in bash (needs `jq`):

```bash
ask() {  # usage: ask <format> <question>
  local id status
  id=$(jq -n --arg q "$2" --arg f "$1" '{question: $q, format: $f}' |
       curl -sf -H 'Content-Type: application/json' -d @- localhost:8080/api/v1/inquiries | jq -r .id)
  while status=$(curl -sf localhost:8080/api/v1/inquiries/$id | jq -r .status); [[ $status == QUEUED || $status == RUNNING ]]; do
    sleep 2
  done
  if [[ $status == DONE ]]; then curl -sf localhost:8080/api/v1/inquiries/$id/result
  else curl -sf localhost:8080/api/v1/inquiries/$id | jq -r .error; fi
}

ask geojson "All stops within 300 m of 51.5178, 7.4593" > stops.geojson
```

## API

### `POST /api/v1/inquiries`

Request body:

| Field | Required | Meaning |
|---|---|---|
| `question` | yes | The question in plain language (German or English), at most 2 000 characters |
| `format` | yes | `json`, `csv`, `xml` or `geojson` (case-insensitive) |

Response: `202 Accepted` with header `Location: /api/v1/inquiries/{id}` and the job (see below).
Errors: `400` problem+json if `question` is missing, blank or too long, or if `format` is not one of the four.

### `GET /api/v1/inquiries/{id}`

Returns the job:

```json
{
  "id": "3f0c2b6e-…",
  "question": "When is the last departure of the U41 from Hauptbahnhof on 2026-07-03?",
  "format": "csv",
  "status": "DONE",
  "createdAt": "2026-10-27T08:12:01Z",
  "startedAt": "2026-10-27T08:12:01Z",
  "finishedAt": "2026-10-27T08:12:09Z",
  "queuePosition": null,
  "error": null,
  "meta": {
    "sql": "select departure_at, departure_time, line, headsign, stop_name from v_departure where feed_id = 19 and effective and service_date = date '2026-07-03' and line = 'U41' and station_name ilike 'dortmund hbf%' and not is_last_stop order by departure_at desc limit 1",
    "previousSql": null,
    "assumptions": ["feed 19 is the current feed and covers 2026-07-03", "last departure means the latest departure_at on that service day"],
    "feeds": [19],
    "rowCount": 1,
    "truncated": false,
    "model": "qwen3.8-27b-32k:latest",
    "modelMillis": 5621,
    "queryMillis": 84,
    "attempts": 1
  }
}
```

| Field | Meaning |
|---|---|
| `status` | `QUEUED` → `RUNNING` → `DONE` or `FAILED` |
| `queuePosition` | 1-based position while `QUEUED`, otherwise `null` |
| `error` | Why the job failed (see [Failures](#failures)) |
| `meta.sql` | The SQL that produced the result. Read it when a result looks wrong. |
| `meta.previousSql` | The SQL of a failed first attempt, if the query had to be repaired |
| `meta.assumptions` | Every interpretation the model chose: feed, date, meaning of vague words |
| `meta.feeds` | Feed ids the query used (as declared by the model, plus `feed_id` values in the result) |
| `meta.rowCount`, `meta.truncated` | Number of rows; `truncated` is true when the 10 000-row cap cut the result |
| `meta.attempts` | 1, or 2 if the first SQL failed and was repaired |

Unknown, malformed or expired ids → `404`.

### `GET /api/v1/inquiries/{id}/result`

The result file with the format's content type:

| Format | Content type | Shape |
|---|---|---|
| `json` | `application/json` | `{"meta": {…}, "rows": [{"column": value, …}]}` |
| `csv` | `text/csv` | Header row plus one line per row (RFC 4180) |
| `xml` | `application/xml` | `<result><meta>…</meta><rows><row><column>…</column></row></rows></result>`; `null` values are empty elements with `nil="true"`; column names that are not valid XML names are mapped to `[A-Za-z0-9_]` |
| `geojson` | `application/geo+json` | `FeatureCollection` with one `Point` per row from the `lat`/`lon` columns, other columns as `properties`, plus a `meta` member |

Headers on every result: `OSCDB-Truncated: true|false` and `OSCDB-Row-Count: <n>`, so CSV and XML callers can see truncation without reading the job.

Not finished yet → `409` (`Inquiry <id> is QUEUED`). Failed → `409` (`Inquiry <id> failed: <error>`). Unknown or expired → `404`.

Column names come from the generated SQL. The model is told to use the views' own snake_case names, but **the same question can come back with slightly different columns on different calls**. Programs should read columns by name and tolerate extra or renamed ones.

## Asking good questions

- **Name the date or period.** Without one, the model uses the *current* feed. If no feed covers today, it falls back to the latest one, and `meta.assumptions` says so. "on 2026-07-01" or "in the first timetable of 2025" makes the answer reproducible.
- **Say what "busiest", "typical" or "in order" means** if it matters. Otherwise the model picks a meaning and lists it under `assumptions`.
- **Use the names passengers see.** The model knows the data's abbreviations ("Hauptbahnhof" → `Dortmund Hbf S U`, `DO-` districts, `Bf`), but unusual names may need the exact spelling.
- **Compare timetables explicitly**: "compare … between the first timetable of 2025 and the latest timetable".
- **Coordinates for GeoJSON**: ask for places or stops; the model adds `lat`/`lon` automatically when `format` is `geojson`.

What the data cannot answer: real-time delays, fares, passenger numbers, routing or journey planning. Those questions fail with `Not answerable: …`.

## Failures

| `error` starts with | Meaning | What to do |
|---|---|---|
| `Not answerable:` | The model found no reasonable reading of the question in this data (e.g. delays) | Rephrase, or the data does not have it |
| `Query failed:` | The SQL failed twice (syntax, unknown column, 30 s timeout, GeoJSON without coordinates) | Make the question more specific; read `meta.sql` and `meta.previousSql` |
| `Model unavailable:` | Ollama is not running, the model is missing, or the model took longer than 120 s | Start Ollama / pull the model, then submit again |
| `Internal error` | Unexpected server error (logged) | Check the application log |

## Limits

| Limit | Default | Key |
|---|---|---|
| Rows per result | 10 000 (then `truncated`) | `oscdb.inquiry.max-rows` |
| SQL run time | 30 s | `oscdb.inquiry.statement-timeout` |
| Model answer time | 120 s | `oscdb.inquiry.model-timeout` |
| Jobs and results kept | 24 h, then 404 | `oscdb.inquiry.retention` |
| Question length | 2 000 characters | `oscdb.inquiry.max-question-length` |
| Parallel jobs | 1 (others wait in the queue) | — |

A restart puts jobs that were `RUNNING` back into the queue before the worker starts.

## What the model can query

Schema `inquiry_api`. The views only show `READY` feeds. Every view has `feed_id`; ids are unique only within one feed.

| View | Content |
|---|---|
| `v_feed` | Timetable periods: id, start/end date, version |
| `v_feed_day` | For each date, the feed valid on it (newest import wins on overlaps) |
| `v_agency` | Operators |
| `v_route` | Lines: `line` (e.g. `U47`), `route_type` (`tram`, `subway`, `rail`, `bus`, `monorail`, `other`), color, agency |
| `v_station` | Stations (grouped platforms), slug id stable across feeds, coordinates, `platform_count` |
| `v_stop` | Stops (platforms) with coordinates and station |
| `v_trip` | Trips with line, headsign, direction, service |
| `v_service_day` | The dates each service runs (calendar and exceptions already applied), with `effective` |
| `v_departure` | One row per stop event on a service day: `departure_time` (`HH:MM:SS`, may exceed 24 h), real `departure_at` (Europe/Berlin), line, headsign, stop, station, first/last stop, `effective` |
| `distance_m(lat1, lon1, lat2, lon2)` | Distance in metres |

`effective` is true when that feed is the valid one for the service date. Normal questions filter on it, so overlapping feeds are never counted twice. Comparisons between feeds filter by `feed_id` instead.

The model's view descriptions are generated from the `COMMENT ON` statements in `V5__inquiry.sql`, together with the prompt template `src/main/resources/inquiry/system-prompt.md`. To improve answers, edit those two files.

## Security model

- Generated SQL runs as the PostgreSQL role `oscdb_inquiry`. That role can only `SELECT` from the `inquiry_api` views and call `distance_m`; it has no access to the base tables or the job table.
- Every query runs in a read-only transaction that is always rolled back, with a per-transaction statement timeout and a row cap. The role is also limited to 1 GB of temporary files (`temp_file_limit`) and 64 MB `work_mem` (`V6__inquiry_limits.sql`), so a runaway query cannot fill the disk.
- Before running, the app rejects anything that is not a single `SELECT`/`WITH` statement. This check only produces early, readable errors; the role is the actual boundary.
- The API has no authentication, like the rest of OSCDB. Under the `local` profile it binds to `127.0.0.1` only.

The role is created by Flyway (`V5__inquiry.sql`); the app sets its password at startup from `oscdb.inquiry.datasource.password`. That needs a database user that may alter roles (true for the local Docker database). For a managed database, create the role, set its password and `temp_file_limit` there.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `oscdb.inquiry.worker-enabled` | `true` (`false` in tests) | Runs the background worker and the hourly cleanup |
| `oscdb.inquiry.ollama-url` | `http://localhost:11434` | Ollama base URL |
| `oscdb.inquiry.model` | `qwen3.8-27b-32k:latest` | Ollama model name |
| `oscdb.inquiry.max-rows` | `10000` | Row cap per result |
| `oscdb.inquiry.statement-timeout` | `30s` | Statement timeout for generated SQL |
| `oscdb.inquiry.model-timeout` | `120s` | Read timeout for one model answer |
| `oscdb.inquiry.retention` | `24h` | How long jobs and results are kept |
| `oscdb.inquiry.max-question-length` | `2000` | Maximum question length |
| `oscdb.inquiry.poll-interval` | `1s` | How often the worker checks the queue |
| `oscdb.inquiry.datasource.url` | main datasource URL | JDBC URL for the restricted role |
| `oscdb.inquiry.datasource.username` | `oscdb_inquiry` | Restricted role |
| `oscdb.inquiry.datasource.password` | `oscdb_inquiry` | Its password (change it outside local use) |

## Evaluation

`scripts/inquiry-eval.sh` submits every question of `scripts/inquiry-eval-questions.txt` to a running instance and prints status, timings, assumptions, SQL and the start of each result:

```
BASE=http://localhost:8080 scripts/inquiry-eval.sh > eval.txt
```

Run on 2026-10-27 against all 19 DSW21 feeds with `qwen3.8-27b-32k:latest` on an Apple M5 Max (36 GB). Today was not covered by any feed, so "current" meant the fallback feed 19 (2026-06-14 to 2026-08-31).

| Question | Format | Result |
|---|---|---|
| Which lines are operated by DSW21? | csv | ✅ 118 lines |
| Next 10 departures at Dortmund Hauptbahnhof on 2026-07-01 after 08:00 | json | ✅ 10 rows (first run: 0 rows, fixed by the name hints in the prompt) |
| Departures of line U47 at Westfalenhallen on 2026-06-15, 17–18 h | csv | ✅ 0 rows — correct: U47 does not serve Westfalenhallen (U45, U46 and buses do) |
| Which stations does line U43 serve, in order? | json | ⚠️ 33 stations, but sorted by station id, not along the line |
| How many trips does line 440 run on a typical Saturday? | csv | ✅ 207 (first Saturday of the feed, stated as assumption) |
| Top 10 busiest stations on 2026-07-01 | csv | ✅ Kampstraße U 1 584, Stadtgarten U 1 397, Reinoldikirche U 1 249 … (query 5 s) |
| Departures on 2026-07-04 per route type | json | ✅ 3 rows |
| Last departure of the U41 from Hauptbahnhof on 2026-07-03 | csv | ✅ `25:30:36` = 2026-07-04 01:30:36 to Hafen U (first run: 0 rows, fixed by the name hints) |
| Trips on a Monday: first 2025 timetable vs latest | csv | ✅ feed 14: 7 353, feed 19: 7 561 (explained why feed 11, a 6-day holiday feed, was skipped) |
| Lines in the latest timetable that did not exist in the first 2024 timetable | json | ✅ 33 lines (feed 5 vs 19) |
| Number of stops across all timetables | csv | ✅ 19 rows, one per feed |
| All stops within 300 m of 51.5178, 7.4593 | geojson | ✅ 11 points |
| Stations of line U42 with coordinates | geojson | ✅ 28 points |
| The 5 stations closest to Dortmund Airport | xml | ✅ 5 rows (first run: refused, fixed by the place hint); ⚠️ the SQL did not restrict `v_station` to one feed |
| How late is the U47 right now? | json | ✅ refused: "only scheduled times, no real-time delays" |
| Which stations have more than 4 platforms? | csv | ✅ 44 stations, Reinoldikirche U has 12 |

Times per question: 6–69 s, of which the database part was 0.05–5 s. Every job that finished needed only one attempt.

Known weak spots: ordering "along the line" and forgetting a `feed_id` filter in a sub-part of a query. Check `meta.sql` when a result matters.
