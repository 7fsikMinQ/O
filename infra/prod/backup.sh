#!/bin/bash
# 일 1회 암호화 덤프(DB + 업로드 파일) → R2. cron 예: 0 18 * * *  (복구 리허설은 분기마다, 28번 §9)
# 필요: rclone(원격 이름 r2), age(공개키 파일), 환경: BACKUP_AGE_RECIPIENT_FILE, 선택: ENV_FILE(기본 저장소 루트의 .env.prod)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/.env.prod}"
DC=(docker compose --env-file "$ENV_FILE" -f "$ROOT/infra/prod/docker-compose.prod.yml")
DB_SUPERUSER="$(grep -m1 '^DB_SUPERUSER=' "$ENV_FILE" | cut -d= -f2- | sed 's/[[:space:]]*#.*//')"
: "${DB_SUPERUSER:?.env.prod 에 DB_SUPERUSER 없음}" "${BACKUP_AGE_RECIPIENT_FILE:?age 공개키 파일 경로 필요}"
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p /var/backups
"${DC[@]}" exec -T postgres pg_dump -U "${DB_SUPERUSER}" -Fc app | age -R "${BACKUP_AGE_RECIPIENT_FILE}" > "/var/backups/app-${STAMP}.dump.age"
rclone copy "/var/backups/app-${STAMP}.dump.age" r2:app-backup/db/
# 업로드 파일(사진 등)도 함께 암호화 백업
VOL="$(docker volume ls -q | grep '_files$' | head -1)"
: "${VOL:?업로드 파일 볼륨(_files)을 찾지 못함}"
docker run --rm -v "${VOL}":/data:ro alpine tar -C /data -c . | age -R "${BACKUP_AGE_RECIPIENT_FILE}" > "/var/backups/files-${STAMP}.tar.age"
rclone copy "/var/backups/files-${STAMP}.tar.age" r2:app-backup/files/
find /var/backups \( -name 'app-*.dump.age' -o -name 'files-*.tar.age' \) -mtime +7 -delete
