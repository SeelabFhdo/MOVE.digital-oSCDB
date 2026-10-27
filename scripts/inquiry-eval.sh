#!/usr/bin/env bash
# Submits every question of a question file to a running OSCDB, waits for each, and prints a report.
# Usage: scripts/inquiry-eval.sh [questions-file]   (BASE defaults to http://localhost:8080)
# Needs curl and jq. Jobs run one at a time on the server, so expect ~10-60 s per question.
set -euo pipefail

BASE=${BASE:-http://localhost:8080}
FILE=${1:-"$(dirname "$0")/inquiry-eval-questions.txt"}

grep -v '^\s*#' "$FILE" | grep -v '^\s*$' | while IFS='|' read -r format question; do
  started=$(date +%s)
  id=$(jq -n --arg q "$question" --arg f "$format" '{question: $q, format: $f}' |
    curl -sf -H 'Content-Type: application/json' -d @- "$BASE/api/v1/inquiries" | jq -r .id)
  while :; do
    job=$(curl -sf "$BASE/api/v1/inquiries/$id")
    status=$(jq -r .status <<<"$job")
    [[ $status == DONE || $status == FAILED ]] && break
    sleep 2
  done
  echo "=== [$format] $question"
  echo "status: $status ($(( $(date +%s) - started )) s, model $(jq -r '.meta.modelMillis // "-"' <<<"$job") ms, query $(jq -r '.meta.queryMillis // "-"' <<<"$job") ms, attempts $(jq -r '.meta.attempts // "-"' <<<"$job"))"
  [[ $status == FAILED ]] && echo "error: $(jq -r .error <<<"$job")"
  echo "rows: $(jq -r '.meta.rowCount // "-"' <<<"$job")  truncated: $(jq -r '.meta.truncated // "-"' <<<"$job")  feeds: $(jq -c '.meta.feeds // []' <<<"$job")"
  echo "assumptions: $(jq -c '.meta.assumptions // []' <<<"$job")"
  echo "sql: $(jq -r '.meta.sql // "-"' <<<"$job")"
  if [[ $status == DONE ]]; then
    echo "result:"
    curl -sf "$BASE/api/v1/inquiries/$id/result" | head -c 600 | head -n 8
    echo
  fi
  echo
done
