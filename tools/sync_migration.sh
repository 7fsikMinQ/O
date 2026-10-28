#!/bin/bash
# db/schema.sql(단일 진실 원천) → Flyway V1 복사. CI 는 --check 로 드리프트를 검사한다.
# 이후 변경은 V2__*.sql 처럼 새 파일로 추가하고 schema.sql 도 함께 갱신한다(CLAUDE.md §5-8).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/db/schema.sql"; DST="$ROOT/backend/src/main/resources/db/migration/V1__baseline.sql"
if [ "${1:-}" = "--check" ]; then
  # V2 이상이 생기면 V1 은 고정(이력 보존)되므로 V1 이 schema.sql 의 '기준 시점' 사본인지만 비교한다
  if [ -n "$(ls "$ROOT"/backend/src/main/resources/db/migration/V[2-9]*.sql 2>/dev/null)" ]; then echo "V2+ 존재: V1 고정, 비교 생략"; exit 0; fi
  cmp -s "$SRC" "$DST" && echo "OK: V1 == schema.sql" || { echo "DRIFT: db/schema.sql 과 V1__baseline.sql 이 다름 (tools/sync_migration.sh 실행)"; exit 1; }
else
  cp "$SRC" "$DST"; echo "복사 완료 → $DST"
fi
