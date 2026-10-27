# Deployment with Docker Compose

`compose.deploy.yaml` starts everything OSCDB needs with one command: the application (built from the
`Dockerfile`), PostgreSQL 17 with a persistent volume, and optionally Ollama for natural-language inquiries.

`compose.yaml` (without `.deploy`) is unrelated: it is only the development database that the `local`
Spring profile starts.

Where the model runs depends on the machine:

| Machine | Ollama | Why |
|---|---|---|
| Mac (Apple Silicon) | Native Ollama on the host | Docker on macOS cannot use the Apple GPU; a 27B model in a container would run on the CPU and take minutes per question |
| Linux server with NVIDIA GPU | Ollama container (`--profile ollama`) | Fully self-contained; the model is pulled automatically on first start |

## Mac: app and database in Docker, model on the host

Prerequisites: Docker Desktop, and Ollama running natively with the model:

```
brew services start ollama          # starts Ollama now and at every login
ollama list                         # must show qwen3.8-27b-32k:latest (or set OSCDB_MODEL)
```

Start:

```
docker compose -f compose.deploy.yaml up -d --build
```

The app reaches the host's Ollama at `http://host.docker.internal:11434`.

## Linux server with NVIDIA GPU: everything in Docker

Prerequisites: Docker with the [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/install-guide.html).

```
docker compose -f compose.deploy.yaml --env-file deploy/ollama-container.env --profile ollama up -d --build
```

This also starts the `ollama` container and the one-shot `ollama-pull` service, which downloads
`qwen3.8:27b` (about 17 GB) into the `ollama` volume. Until the download has finished, inquiries fail with
`Model unavailable`; the rest of the API works immediately. Follow the download with
`docker compose -f compose.deploy.yaml --profile ollama logs -f ollama-pull`.

Without a GPU, delete the `deploy:` block of the `ollama` service. It then runs on the CPU and is very slow.

## What happens on start

1. PostgreSQL starts; the app waits until it is healthy.
2. The app applies the Flyway migrations and imports every GTFS zip from the data directory (all 19 DSW21 feeds:
   about 2 minutes on the first start). Later starts skip zips that are already imported.
3. The API is available at `http://localhost:8080` (Swagger UI: `/swagger-ui.html`).

Check: `curl -s localhost:8080/api/v1/feeds | jq .total`

New GTFS zips: copy them into the data directory, then either restart the app
(`docker compose -f compose.deploy.yaml restart app`) or trigger a rescan:
`curl -X POST localhost:8080/api/v1/admin/imports`.

## Settings

Set them in the shell, in a `.env` file next to `compose.deploy.yaml`, or in a file passed with `--env-file`.

| Variable | Default | Meaning |
|---|---|---|
| `OSCDB_PORT` | `8080` | Host port of the API (bound to `127.0.0.1` only) |
| `GTFS_DATA_DIR` | `../Daten` | Host directory with the GTFS zips, mounted read-only |
| `OLLAMA_URL` | `http://host.docker.internal:11434` | Ollama endpoint; `http://ollama:11434` with the `ollama` profile |
| `OSCDB_MODEL` | `qwen3.8-27b-32k:latest` | Model name; `qwen3.8:27b` with the `ollama` profile (pulled automatically) |
| `POSTGRES_PASSWORD` | `oscdb` | Database password |
| `INQUIRY_DB_PASSWORD` | `oscdb_inquiry` | Password of the restricted role that runs generated SQL |
| `OSCDB_ADMIN_ENDPOINT` | `true` | Enables `POST /api/v1/admin/imports` |

Change both passwords before running anywhere but your own machine. The API has no authentication;
it is published on `127.0.0.1` only. Put a reverse proxy with authentication in front of it before
exposing it.

## Operations

```
docker compose -f compose.deploy.yaml ps                 # status
docker compose -f compose.deploy.yaml logs -f app        # application log (import, inquiries)
docker compose -f compose.deploy.yaml up -d --build      # rebuild and restart after code changes
docker compose -f compose.deploy.yaml down               # stop; data stays in the volumes
docker compose -f compose.deploy.yaml down -v            # stop and DELETE the database (and model) volumes
```

Add `--profile ollama` to these commands when you use the Ollama container, so its services are included.
