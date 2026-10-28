#!/bin/bash
# 일 1회 암호화 덤프 → R2. cron: 0 3 * * *  (복구 리허설은 분기마다, 11번 문서)
# 필요: rclone(원격 이름 r2), age(공개키 파일), 환경: BACKUP_AGE_RECIPIENT_FILE
set -euo pipefail
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
docker compose -f "$(dirname "$0")/docker-compose.prod.yml" exec -T postgres \
  pg_dump -U "${DB_SUPERUSER}" -Fc app | age -R "${BACKUP_AGE_RECIPIENT_FILE}" > "/var/backups/app-${STAMP}.dump.age"
rclone copy "/var/backups/app-${STAMP}.dump.age" r2:app-backup/db/
find /var/backups -name 'app-*.dump.age' -mtime +7 -delete
