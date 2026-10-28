#!/bin/bash
# 개발 DB 초기화(데이터 전부 삭제) 후 다시 기동. 첫 운영 배포 전에는 V1__baseline.sql 을 schema.sql 과 맞춰 다시 만들 수 있어,
# 그때마다 'Migration checksum mismatch' 가 나면 이 스크립트로 개발 DB 를 비우고 시작한다. 운영 DB 에는 절대 쓰지 않는다.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
read -r -p "개발 DB 의 모든 데이터를 지웁니다. 계속할까요? (y/N) " ans
[ "$ans" = "y" ] || { echo "취소"; exit 0; }
docker compose -f "$ROOT/infra/docker-compose.dev.yml" --env-file "$ROOT/.env" down -v
"$ROOT/tools/dev/up.sh"
