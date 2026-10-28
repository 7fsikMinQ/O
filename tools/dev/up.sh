#!/bin/bash
# 개발 DB·메일함 기동(WSL2 의 Docker Engine). 포트는 127.0.0.1 에만 열린다.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
[ -f "$ROOT/.env" ] || cp "$ROOT/.env.example" "$ROOT/.env"
docker compose -f "$ROOT/infra/docker-compose.dev.yml" --env-file "$ROOT/.env" up -d
echo "DB: postgresql://app:app@localhost:5432/app  ·  메일함: http://localhost:8025"
