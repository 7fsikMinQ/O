# 보조출연 헬프

영화·드라마 보조출연 당일/단기 인력 매칭 플랫폼. **설계는 `docs/00_README.md`, 프로젝트 기억은 `CLAUDE.md`.**

| 폴더 | 내용 |
|---|---|
| `db/` | `schema.sql`(DB 단일 진실 원천) · `future/` |
| `backend/` | Spring Boot 4.1 + Java 25 (Gradle). Flyway 마이그레이션은 `src/main/resources/db/migration` |
| `web/` | 업체·관리자 웹 (React 19 + Vite + TypeScript) |
| `app/` | 출연자 앱 (Flutter, Android + 웹/PWA) |
| `infra/` | 개발 compose(`docker-compose.dev.yml`), 운영(`prod/`) |
| `tools/` | 검증·생성 스크립트 (`verify_all.sh`, `build_docs.py`, `check_versions.sh` …) |
| `docs/` | 설계 문서 (자동 생성 + 손글씨) |

## 빠른 시작 (WSL2 Ubuntu 24.04 기준, 처음부터는 `docs/25_새PC_설치와_브랜치_운영.md`)
```bash
git clone -b dev/mk https://github.com/7fsikMinQ/O.git bojohelp && cd bojohelp
tools/dev/bootstrap.sh --check    # 설치 점검
tools/dev/bootstrap.sh            # DB 기동 + 백엔드 시험
cd backend && ./gradlew bootRun   # 서버 실행
```
시작 순서는 `docs/24_개발착수_순서.md`, 버전 근거는 `docs/23_개발착수_버전호환표.md`.
