#!/bin/bash
# 전체 검증 일괄 실행. 필요: PostgreSQL(psql/createdb/pgbench, PG* 환경변수), python3, (선택) node+mermaid+jsdom
# 사용: PGHOST=localhost PGUSER=postgres tools/verify_all.sh
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
FAIL=0; ok(){ echo "  ✔ $*"; }; ng(){ echo "  ✘ $*"; FAIL=1; }
D=verify_$$; dropdb --if-exists $D >/dev/null 2>&1; createdb $D

echo "[1] 스키마 적용";            psql -q -v ON_ERROR_STOP=1 -d $D -f db/schema.sql >/dev/null 2>/tmp/v1.err && ok "schema.sql" || { ng "schema.sql"; cat /tmp/v1.err; }
echo "[2] 제약 시험 ×3(재실행성)"; for i in 1 2 3; do N=$(psql -d $D -f tools/test_schema.sql 2>&1 | grep -c "NOTICE:  OK"); [ "$N" -eq 22 ] && ok "run$i: OK=$N" || ng "run$i: OK=$N"; done
echo "[3] 확장 DDL 적용";          psql -q -v ON_ERROR_STOP=1 -d $D -f db/future/monetization_draft.sql >/dev/null 2>&1 && ok "monetization_draft.sql" || ng "monetization_draft.sql"
echo "[4] 동시성 실측";            bash tools/concurrency_test.sh 2>&1 | tail -3 | sed 's/^/    /'; bash tools/concurrency_test.sh >/dev/null 2>&1 && ok "pgbench" || ng "pgbench"
echo "[5] 문서 생성·연결 검증";    dropdb --if-exists ${D}_doc >/dev/null 2>&1; createdb ${D}_doc; psql -q -v ON_ERROR_STOP=1 -d ${D}_doc -f db/schema.sql >/dev/null 2>&1
TAB=$(printf '\t'); export DOC_PSQL="psql -d ${D}_doc -AtX -F '$TAB'"
python3 tools/build_docs.py | tail -3 | sed 's/^/    /'; python3 tools/build_docs.py >/dev/null && ok "build_docs" || ng "build_docs"
echo "[6] 마크다운 링크";          python3 - <<'PY' && ok "링크" || ng "링크"
import re,os,sys
bad=0
for root,_,fs in os.walk('.'):
    if '.git' in root or 'node_modules' in root: continue
    for f in fs:
        if f.endswith('.md'):
            p=os.path.join(root,f); t=open(p,encoding='utf-8').read()
            for m in re.finditer(r'\]\(([^)#\s]+)(#[^)]*)?\)',t):
                u=m.group(1)
                if u.startswith(('http','mailto')): continue
                if not os.path.exists(os.path.normpath(os.path.join(root,u))): print("BROKEN",p,u); bad+=1
sys.exit(1 if bad else 0)
PY
echo "[7] API 예시 JSON";         python3 - <<'PY' && ok "JSON" || ng "JSON"
import re,json,sys
t=open('docs/08_API명세서.md',encoding='utf-8').read(); bad=0
for b in re.findall(r"```json\n(.*?)```",t,re.S):
    try: json.loads(b)
    except Exception as e: print("JSON 오류",e); bad+=1
sys.exit(1 if bad else 0)
PY
echo "[8] 폐기·정정된 개념/수치 잔존 스캔"; PAT="WAITLISTED|SHORTLISTED|FIRST_COME|reliability_score|reliability_events|apply_blocked_until|sensitive_purge|confirmed_time_range|build web --release --wasm|1시간~7일|최대 7일|8~9원|월 약 US[$]10|Spring Boot 3\\.|call_time TIME"
HITS=$(grep -nE "$PAT" docs/0*.md docs/1[0-5]*.md CLAUDE.md db/schema.sql tools/spec/*.md 2>/dev/null | grep -vE "예비합격 없음|부록 C|예비합격 제도 없음|3\.5 는 2026|Spring Boot 3\.5|3\.x 와 의존성|^docs/00_README.md:[0-9]+:\| v1\.[01] |이전 추정 8~9원 정정" || true)
[ -z "$HITS" ] && ok "없음" || { ng "잔존"; echo "$HITS" | head; }
echo "[9] 비밀정보 패턴";          ! grep -rniE "(api[_-]?key|secret|password)\s*[:=]\s*['\"]?[A-Za-z0-9+/]{20,}" . --exclude-dir=.git --exclude-dir=node_modules 2>/dev/null | grep -vE "change-me|base64-32byte|example|minio12345" | grep -q . && ok "없음" || ng "의심 패턴"
echo "[10] mermaid 문법(선택)";   MD="${MERMAID_DIR:-$ROOT/tools}"; if command -v node >/dev/null && (cd "$MD" && node -e "require.resolve('mermaid')" >/dev/null 2>&1); then cp "$ROOT/tools/check_mermaid.mjs" "$MD/check_mermaid.mjs" 2>/dev/null; R=$(cd "$MD" && node check_mermaid.mjs "$ROOT"/docs/*.md 2>&1 | tail -3); echo "    $R"; echo "$R" | grep -q "failed: 0" && ok "mermaid" || ng "mermaid"; else echo "  - 건너뜀(node/mermaid 미설치: npm i mermaid jsdom 후 MERMAID_DIR 지정)"; fi
dropdb --if-exists $D >/dev/null 2>&1; dropdb --if-exists ${D}_doc >/dev/null 2>&1
[ $FAIL -eq 0 ] && echo "ALL PASS" || { echo "FAILED"; exit 1; }
