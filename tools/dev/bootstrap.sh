#!/bin/bash
# 새 PC/새 WSL 에서 저장소를 받은 뒤 한 번에 점검·기동·시험:  tools/dev/bootstrap.sh [--check] [--skip-tests]
#   --check       : 설치 상태만 점검(아무것도 기동하지 않음)
#   --skip-tests  : DB 기동까지만
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CHECK_ONLY=0; SKIP_TESTS=0
for a in "$@"; do case "$a" in --check) CHECK_ONLY=1;; --skip-tests) SKIP_TESTS=1;; esac; done
FAIL=0
ok(){ echo "  ✔ $*"; }
ng(){ echo "  ✘ $*"; FAIL=1; }
warn(){ echo "  ! $*"; }

echo "[1] 필수 도구"
command -v git >/dev/null && ok "git $(git --version | awk '{print $3}')" || ng "git 없음: sudo apt install -y git"
if command -v docker >/dev/null; then
  if docker info >/dev/null 2>&1; then ok "docker $(docker --version | awk '{print $3}' | tr -d ,) (데몬 동작, 권한 OK)"
  else ng "docker 는 있으나 사용 불가: 데몬이 꺼졌거나 권한 없음 → sudo systemctl enable --now docker / sudo usermod -aG docker \$USER 후 WSL 창 다시 열기"; fi
  docker compose version >/dev/null 2>&1 && ok "docker compose" || ng "docker compose 플러그인 없음(공식 설치 스크립트로 설치)"
else ng "docker 없음: curl -fsSL https://get.docker.com | sudo sh"; fi
JV=$(java -version 2>&1 | grep -m1 ' version ')
case "$JV" in *'"25'*) ok "JDK 25 ($JV)";; *) ng "JDK 25 필요(현재: ${JV:-없음}) → sdk install java 25-tem";; esac
if command -v node >/dev/null; then
  NV=$(node -v | tr -d v); MAJ=${NV%%.*}
  [ "$MAJ" -ge 22 ] 2>/dev/null && ok "node $NV (웹용)" || warn "node $NV — 웹은 22.22 이상(24 LTS 권장), 백엔드만 할 땐 무시"
else warn "node 없음 — 웹 작업할 때 필요(nvm 으로 24 LTS 설치)"; fi
if command -v flutter >/dev/null; then
  FV=$(flutter --version 2>/dev/null | head -1 | awk '{print $2}')
  case "$FV" in 3.47.*) ok "flutter $FV (앱용)";; *) warn "flutter $FV — 3.47.x 필요(docs/25 §2-2)";; esac
else warn "flutter 없음 — 앱 작업할 때 필요(docs/25 §2-2)"; fi
case "$ROOT" in /mnt/*) ng "프로젝트가 /mnt(Windows 디스크) 아래에 있음 — 매우 느리다. WSL 홈(~/)으로 옮길 것";; *) ok "프로젝트 위치 $ROOT";; esac

[ $FAIL -ne 0 ] && { echo; echo "위 ✘ 항목을 먼저 해결하세요. (docs/25_새PC_설치와_브랜치_운영.md)"; exit 1; }
[ $CHECK_ONLY -eq 1 ] && { echo; echo "점검 통과"; exit 0; }

echo "[2] 개발 DB 기동"
[ -f "$ROOT/.env" ] || { cp "$ROOT/.env.example" "$ROOT/.env"; echo "  .env 생성(.env.example 복사)"; }
docker compose -f "$ROOT/infra/docker-compose.dev.yml" --env-file "$ROOT/.env" up -d || { echo "기동 실패(포트 5432 사용 중이면 .env 의 DB_PORT 를 5433 으로)"; exit 1; }
for i in $(seq 1 30); do
  docker compose -f "$ROOT/infra/docker-compose.dev.yml" --env-file "$ROOT/.env" ps postgres 2>/dev/null | grep -q healthy && break
  sleep 2
done
docker compose -f "$ROOT/infra/docker-compose.dev.yml" --env-file "$ROOT/.env" ps postgres | grep -q healthy && ok "PostgreSQL 준비됨" || ng "PostgreSQL 이 healthy 가 되지 않음(docker compose logs postgres)"

if [ $SKIP_TESTS -eq 0 ] && [ $FAIL -eq 0 ]; then
  echo "[3] 백엔드 시험(Testcontainers 가 postgres:18 을 따로 띄움, 몇 분 걸림)"
  (cd "$ROOT/backend" && ./gradlew test --no-daemon -q) && ok "백엔드 시험 통과" || ng "백엔드 시험 실패(위 로그 확인)"
fi
echo
[ $FAIL -eq 0 ] && { echo "준비 완료. 서버 실행: cd backend && ./gradlew bootRun   (SSH 가 아니라 'wsl' 창을 연 채로 작업)"; exit 0; } || exit 1
