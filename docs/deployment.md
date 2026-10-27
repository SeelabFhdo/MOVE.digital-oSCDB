# Deploy and run with Docker

OSCDB runs as three parts:

| Part | Runs in | Needed for |
|---|---|---|
| Application (`oscdb`) | Docker, built from the `Dockerfile` | everything |
| PostgreSQL 17 | Docker, data kept in a volume | everything |
| Ollama with a language model | Native on a Mac, or Docker on a Linux GPU server | only the natural-language inquiries |

`compose.deploy.yaml` starts the parts with one command. `compose.yaml` (without `.deploy`) is unrelated: it is
only the development database that the `local` Spring profile uses.

Why the model's location depends on the machine: Docker on macOS cannot use the Apple GPU. A 27B model in a
container would run on the CPU and take minutes per question, while native Ollama on the Mac takes seconds.

## Step by step on a Mac

### 1. Install the prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/), running.
- Ollama with the model, only needed for inquiries:

  ```
  brew install ollama
  brew services start ollama          # starts Ollama now and at every login
  ollama pull qwen3.8:27b             # about 17 GB, once
  ```

  This machine already has the custom model `qwen3.8-27b-32k:latest`, which is the default. With the registry
  model, set `OSCDB_MODEL=qwen3.8:27b` in step 3.

### 2. Put the code and the GTFS data side by side

```
MOVE.digital/
├── OSCDB/     ← this repository
└── Daten/     ← GTFS zips, in any subfolders (e.g. 2026/140626_310826_gtfs_dsw21.zip)
```

Another location works too: set `GTFS_DATA_DIR` in step 3.

### 3. Optional: settings

Create `OSCDB/.env` only if you need to change a default (see [Settings](#settings)), for example:

```
OSCDB_PORT=8080
GTFS_DATA_DIR=/Volumes/data/gtfs
POSTGRES_PASSWORD=change-me
INQUIRY_DB_PASSWORD=change-me-too
```

### 4. Build and start

```
cd OSCDB
docker compose -f compose.deploy.yaml up -d --build
```

The first build downloads Maven dependencies and takes a few minutes. Then:

1. PostgreSQL starts; the app waits until it is healthy.
2. The app creates the schema and imports every GTFS zip. All 19 DSW21 feeds take about 2 minutes the first time.
   Later starts skip zips that are already imported.

Follow the start-up:

```
docker compose -f compose.deploy.yaml logs -f app      # wait for "Import finished: 19 imported"
```

### 5. Check that it runs

```
curl -s localhost:8080/api/v1/feeds/current | jq '{id, startDate, endDate, fallback}'
```

Open the API explorer at http://localhost:8080/swagger-ui.html.

Test an inquiry, which needs Ollama:

```
curl -s -X POST localhost:8080/api/v1/inquiries -H 'Content-Type: application/json' \
  -d '{"question": "Which stations have more than 8 platforms?", "format": "csv"}'
# then poll GET /api/v1/inquiries/{id} and download /api/v1/inquiries/{id}/result
```

Or run the full tour, which prints every step with its output:

```
examples/walkthrough.sh
```

The result of that tour is [example.md](example.md).

## Step by step on a Linux server with an NVIDIA GPU

Here Ollama also runs in Docker, so one command starts everything.

1. Install Docker and the [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/install-guide.html).
   Check with `docker run --rm --gpus all ubuntu nvidia-smi`.
2. Copy the repository and the GTFS data to the server, laid out as in step 2 above.
3. Change the passwords in `.env` (see [Settings](#settings)).
4. Start with the `ollama` profile:

   ```
   cd OSCDB
   docker compose -f compose.deploy.yaml --env-file deploy/ollama-container.env --profile ollama up -d --build
   ```

   This also starts the `ollama` container and the one-shot `ollama-pull` service, which downloads `qwen3.8:27b`
   (about 17 GB) into the `ollama` volume. The REST API works right away. Inquiries fail with
   `Model unavailable` until the download has finished:

   ```
   docker compose -f compose.deploy.yaml --profile ollama logs -f ollama-pull
   ```

5. Check as in step 5 above.

Without a GPU, remove the `deploy:` block of the `ollama` service in `compose.deploy.yaml`. It then runs on
the CPU, which is very slow.

The API is published on `127.0.0.1` of the server only. To reach it from other machines, put a reverse proxy
with authentication in front of it (OSCDB itself has no authentication). Do not change the port binding to `0.0.0.0`.

## Without Compose: plain `docker run`

The same deployment by hand, for example to plug the app into an existing PostgreSQL:

```
docker build -t oscdb .
docker network create oscdb-net

docker run -d --name oscdb-db --network oscdb-net \
  -e POSTGRES_DB=oscdb -e POSTGRES_USER=oscdb -e POSTGRES_PASSWORD=oscdb \
  -v oscdb-pgdata:/var/lib/postgresql/data \
  postgres:17

docker run -d --name oscdb-app --network oscdb-net \
  -p 127.0.0.1:8080:8080 \
  -v "$(cd .. && pwd)/Daten:/data:ro" \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://oscdb-db:5432/oscdb \
  -e SPRING_DATASOURCE_USERNAME=oscdb \
  -e SPRING_DATASOURCE_PASSWORD=oscdb \
  -e OSCDB_IMPORT_DATA_DIR=/data \
  -e OSCDB_INQUIRY_OLLAMA_URL=http://host.docker.internal:11434 \
  --add-host host.docker.internal:host-gateway \
  oscdb
```

Every `oscdb.*` and `spring.*` property can be set this way as an environment variable: upper-case it and
replace `.` and `-` with `_`. For example, `oscdb.inquiry.model` becomes `OSCDB_INQUIRY_MODEL`.

The database user must be allowed to create roles, because the migrations create the restricted role
`oscdb_inquiry`. The `postgres` image's user is allowed to. For a managed database, see the security section of
[inquiries.md](inquiries.md#security-model).

## Day-to-day operation

| Task | Command |
|---|---|
| Status | `docker compose -f compose.deploy.yaml ps` |
| Application log | `docker compose -f compose.deploy.yaml logs -f app` |
| Import new GTFS zips | Copy them into the data folder, then `curl -X POST localhost:8080/api/v1/admin/imports` (or restart the app) |
| Deploy a new version | `git pull`, then `docker compose -f compose.deploy.yaml up -d --build` |
| Stop (data is kept) | `docker compose -f compose.deploy.yaml down` |
| Start again | `docker compose -f compose.deploy.yaml up -d` |
| Delete everything, including the database | `docker compose -f compose.deploy.yaml down -v` |
| Database shell | `docker compose -f compose.deploy.yaml exec postgres psql -U oscdb oscdb` |

When you use the Ollama container, add `--profile ollama` to these commands so its services are included.
Use `--env-file deploy/ollama-container.env` on `up` as well.

A new version applies its database migrations on start. Imported feeds stay; there is no re-import.

## Settings

Set them in `OSCDB/.env`, in the shell, or in a file passed with `--env-file`.

| Variable | Default | Meaning |
|---|---|---|
| `OSCDB_PORT` | `8080` | Host port of the API (bound to `127.0.0.1` only) |
| `GTFS_DATA_DIR` | `../Daten` | Host folder with the GTFS zips, mounted read-only |
| `OLLAMA_URL` | `http://host.docker.internal:11434` | Ollama endpoint. Use `http://ollama:11434` with the `ollama` profile |
| `OSCDB_MODEL` | `qwen3.8-27b-32k:latest` | Model name. Use `qwen3.8:27b` with the `ollama` profile (pulled automatically) |
| `POSTGRES_PASSWORD` | `oscdb` | Database password |
| `INQUIRY_DB_PASSWORD` | `oscdb_inquiry` | Password of the restricted role that runs generated SQL |
| `OSCDB_ADMIN_ENDPOINT` | `true` | Enables `POST /api/v1/admin/imports` |

Change both passwords before you run OSCDB anywhere but your own machine.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `port is already allocated` or `address already in use` on start | Another process (e.g. OSCDB started from the IDE) uses 8080. Stop it or set `OSCDB_PORT=8090`. |
| `Import finished: 0 imported, 0 skipped` | The data folder is empty or wrong inside the container. Check `GTFS_DATA_DIR` and `docker compose -f compose.deploy.yaml exec app ls /data`. |
| Inquiries fail with `Model unavailable` | Ollama is not running or not reachable. On the Mac, run `brew services start ollama` and `ollama list`. With the profile, wait for `ollama-pull`. |
| Inquiries fail with `Model unavailable: … model … not found` | `OSCDB_MODEL` names a model Ollama does not have: `ollama pull <model>`. |
| The first inquiry is slow | Ollama loads the model (18 GB) into memory on the first request after a while idle. |
