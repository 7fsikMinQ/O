#!/usr/bin/env python3
"""명세 데이터(tools/spec/*.py) + DB 스키마(db/schema.sql)에서 문서를 생성하고 상호 일관성을 검증한다.

사용:
  DOC_PSQL='psql -h localhost -U postgres -d docdb -AtX -F "$(printf \\"\\\\t\\")"' python3 tools/build_docs.py
  (스키마를 적용한 PostgreSQL 에 접속 가능한 psql 명령을 DOC_PSQL 로 지정. SQL 은 stdin 으로 전달됨)
검증 실패 시 종료코드 1.
"""
import os, re, subprocess, sys, collections
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools", "spec"))
from reqs import REQS
from funcs import FUNCS
from screens import SCREENS
from apis import APIS
from programs import PROGRAMS

DOCS = os.path.join(ROOT, "docs")
SRC = os.path.join(ROOT, "tools", "spec")
TAB = "\t"

# ───────────────────────── 스키마 읽기 ─────────────────────────
def psql(sql):
    cmd = os.environ.get("DOC_PSQL")
    if not cmd:
        sys.exit("DOC_PSQL 환경변수가 필요합니다 (스키마가 적용된 DB 접속 psql 명령)")
    r = subprocess.run(cmd, shell=True, input=sql, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("psql 실패: " + r.stderr)
    return [l.split(TAB) for l in r.stdout.splitlines() if l.strip()]

COLS = psql("""SELECT c.relname, a.attnum, a.attname, format_type(a.atttypid,a.atttypmod), a.attnotnull::text,
  COALESCE(pg_get_expr(d.adbin,d.adrelid),''), a.attidentity::text
FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
WHERE n.nspname='public' AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped ORDER BY c.relname,a.attnum;""")
CONS = psql("""SELECT conrelid::regclass::text, conname, contype::text, pg_get_constraintdef(oid), COALESCE(confrelid::regclass::text,'')
FROM pg_constraint WHERE connamespace='public'::regnamespace ORDER BY 1,2;""")
IDX = psql("SELECT tablename, indexname, indexdef FROM pg_indexes WHERE schemaname='public' ORDER BY 1,2;")
VIEWS = [r[0] for r in psql("SELECT viewname FROM pg_views WHERE schemaname='public' ORDER BY 1;")]

TABLES = collections.OrderedDict()
for t, num, name, typ, nn, dflt, ident in COLS:
    TABLES.setdefault(t, []).append(dict(name=name, type=typ, notnull=nn == "true", default=dflt, identity=ident))
TABLE_NAMES = set(TABLES)

# 주석 파싱 (schema.sql 의 -- 설명)
COMMENTS, TABLE_COMMENT = {}, {}
SCHEMA = open(os.path.join(ROOT, "db", "schema.sql"), encoding="utf-8").read()
cur = None; last = None
for line in SCHEMA.splitlines():
    m = re.match(r"CREATE TABLE (\w+)\s*\((.*)$", line)
    if m:
        cur = m.group(1); last = None
        c = re.search(r"--\s*(.*)$", m.group(2))
        if c: TABLE_COMMENT[cur] = c.group(1).strip()
        continue
    if cur is None: continue
    if re.match(r"^\);", line): cur = None; continue
    cm = re.match(r"^\s{2}([a-z_0-9]+)\s+[A-Za-z]", line)
    if cm and cm.group(1).upper() not in ("CONSTRAINT", "CHECK", "UNIQUE", "PRIMARY", "EXCLUDE", "FOREIGN"):
        last = cm.group(1)
        c = re.search(r"--\s*(.*)$", line)
        if c: COMMENTS[(cur, last)] = c.group(1).strip()
    else:
        c = re.match(r"^\s{10,}--\s*(.*)$", line)       # 깊게 들여쓴 주석만 이전 컬럼의 연속 설명으로 취급
        if c and last: COMMENTS[(cur, last)] = (COMMENTS.get((cur, last), "") + " " + c.group(1).strip()).strip()

FKS = collections.defaultdict(list)      # table -> [(col, ref_table)]
PK = collections.defaultdict(list); UQ = collections.defaultdict(set)
CHECKS = collections.defaultdict(list)
for t, name, typ, defn, ref in CONS:
    if typ == "f":
        col = re.search(r"FOREIGN KEY \(([^)]+)\)", defn).group(1)
        FKS[t].append((col, ref))
    elif typ == "p":
        PK[t] = [c.strip() for c in re.search(r"\(([^)]+)\)", defn).group(1).split(",")]
    elif typ == "u":
        UQ[t].add(re.search(r"\(([^)]+)\)", defn).group(1))
    elif typ in ("c", "x"):
        CHECKS[t].append((name, defn))

ALL_SCHEMA_IDS = set(TABLE_NAMES) | set(VIEWS)
SETTINGS_KEYS = set(re.findall(r"\('([a-z_]+)','[^']*','", SCHEMA.split("INSERT INTO app_settings")[1].split(";")[0])) if "INSERT INTO app_settings" in SCHEMA else set()

GROUPS = collections.OrderedDict([
 ("계정·권한", "otp_challenges account_deletion_requests users user_auth_providers roles permissions role_permissions user_roles refresh_tokens login_histories terms user_consents"),
 ("마스터·파일", "regions position_categories productions minimum_wages files file_attachments"),
 ("프로필·업체·구독", "actor_profiles actor_payout_accounts actor_tax_identities actor_penalty_events guardian_consents companies company_members company_verifications company_actor_blocks company_starred_actors subscription_plans plan_features company_subscriptions usage_counters"),
 ("공고·지원", "job_postings job_schedules job_positions applications application_status_histories schedule_conflicts cancellation_reviews cancellation_review_votes bookmarks saved_searches reviews"),
 ("현장·메시지·정산", "job_broadcasts job_broadcast_recipients actor_company_blocks message_threads messages attendance_sessions attendances settlements settlement_items export_templates payout_batches payout_batch_items"),
 ("게시판·알림·운영", "boards board_permissions posts comments notification_templates notifications notification_deliveries notification_settings device_tokens outbox_events reports audit_logs app_settings feature_flags"),
])
GROUPS = collections.OrderedDict((k, v.split()) for k, v in GROUPS.items())

# ───────────────────────── 검증 ─────────────────────────
errors, warnings = [], []
def err(m): errors.append(m)
def warn(m): warnings.append(m)
RANK = {"P1": 1, "P2": 2, "P3": 3}

def uniq(ids, kind):
    d = [k for k, c in collections.Counter(ids).items() if c > 1]
    for k in d: err(f"{kind} ID 중복: {k}")
uniq([r[0] for r in REQS], "REQ"); uniq([f[0] for f in FUNCS], "FUNC"); uniq([s[0] for s in SCREENS], "SCR")
uniq([a[0] for a in APIS], "API"); uniq([p[0] for p in PROGRAMS], "PGM")
rid = {r[0]: r for r in REQS}; fid = {f[0]: f for f in FUNCS}; sid = {s[0]: s for s in SCREENS}
aid = {a[0]: a for a in APIS}; pid = {p[0]: p for p in PROGRAMS}

used_req, used_scr, used_api, used_pgm = set(), set(), set(), set()
for f in FUNCS:
    i, n, d, reqs, who, pri, scr, api, pgm = f
    if not reqs: err(f"{i}: 요구사항 연결 없음")
    for x in reqs:
        used_req.add(x)
        if x not in rid: err(f"{i}: 존재하지 않는 {x}")
    for x in scr:
        used_scr.add(x)
        if x not in sid: err(f"{i}: 존재하지 않는 {x}")
    for x in api:
        used_api.add(x)
        if x not in aid: err(f"{i}: 존재하지 않는 {x}")
    for x in pgm:
        used_pgm.add(x)
        if x not in pid: err(f"{i}: 존재하지 않는 {x}")
    if not pgm: err(f"{i}: 프로그램 연결 없음")
    if reqs and all(x in rid for x in reqs):
        best = min(RANK[rid[x][4]] for x in reqs)
        worst = max(RANK[rid[x][4]] for x in reqs)
        if RANK[pri] < best: warn(f"{i}: 우선순위 {pri} 가 연결 요구사항(최고 P{best})보다 앞섬")
        if RANK[pri] > worst: warn(f"{i}: 우선순위 {pri} 가 연결 요구사항(최저 P{worst})보다 뒤임")
for r in REQS:
    if r[0] not in used_req: err(f"{r[0]} 를 구현하는 기능이 없음")
for s in SCREENS:
    if s[0] not in used_scr: err(f"{s[0]} 화면이 어떤 기능에도 연결되지 않음")
for a in APIS:
    if a[0] not in used_api: err(f"{a[0]} API 가 어떤 기능에도 연결되지 않음")
for p in PROGRAMS:
    if p[0] not in used_pgm: err(f"{p[0]} 프로그램이 어떤 기능에도 연결되지 않음")
sigs = [(a[1], a[2]) for a in APIS]
for k, c in collections.Counter(sigs).items():
    if c > 1: err(f"API 메서드+경로 중복: {k}")
for a in APIS:
    for t in [x for x in a[6].split(",") if x]:
        if t not in ALL_SCHEMA_IDS: err(f"{a[0]}: 스키마에 없는 테이블/뷰 '{t}'")
    if a[4] not in ("PUB","USER","ACTOR","CMP","CMP-M","CMP-F","ADM","OPR"): err(f"{a[0]}: 알 수 없는 권한 {a[4]}")
allgrp = {t for v in GROUPS.values() for t in v}
for t in TABLE_NAMES - allgrp: err(f"ERD 그룹에 없는 테이블: {t}")
for t in allgrp - TABLE_NAMES: err(f"그룹에 있으나 스키마에 없는 테이블: {t}")
for s in SCREENS:
    fs = [f for f in FUNCS if s[0] in f[6]]
    if fs:
        best = min(RANK[f[5]] for f in fs)
        if RANK[s[4]] != best: warn(f"{s[0]} 화면 우선순위 {s[4]} 와 연결 기능 최고 우선순위 P{best} 불일치")

# ───────────────────────── 유틸 ─────────────────────────
def esc(s): return str(s).replace("|", "\\|").replace("\n", " ")
def table(head, rows):
    out = ["| " + " | ".join(head) + " |", "|" + "|".join(["---"] * len(head)) + "|"]
    out += ["| " + " | ".join(esc(c) for c in r) + " |" for r in rows]
    return "\n".join(out)
def read_src(name):
    p = os.path.join(SRC, name)
    return open(p, encoding="utf-8").read() if os.path.exists(p) else ""
def write(name, text):
    with open(os.path.join(DOCS, name), "w", encoding="utf-8") as f: f.write(text.rstrip() + "\n")
HDR = "> 이 문서는 `tools/spec/*.py` 와 `db/schema.sql` 에서 `python3 tools/build_docs.py` 로 **자동 생성**된다. 직접 수정하지 말고 원천 데이터를 고칠 것.\n> 버전 v1.0 · 기준일 2026-10-28\n"
who_pri = {"P1": "P1 (MVP)", "P2": "P2 (오픈 후)", "P3": "P3 (확장)"}
SRCMAP = {"U": "사용자 요구", "R": "검토 반영", "L": "법·보안", "X": "확장 대비"}

# ───────────────────────── 01 요구사항정의서 ─────────────────────────
def doc01():
    out = ["# 01. 요구사항 정의서", HDR, f"총 **{len(REQS)}**건 (P1 {sum(1 for r in REQS if r[4]=='P1')} / P2 {sum(1 for r in REQS if r[4]=='P2')} / P3 {sum(1 for r in REQS if r[4]=='P3')})",
           "", "출처 구분: **사용자 요구**(운영 당사자 요구), **검토 반영**(도메인·상용 검토), **법·보안**, **확장 대비**.", ""]
    grp = collections.OrderedDict()
    for r in REQS: grp.setdefault(r[1], []).append(r)
    for g, rs in grp.items():
        out += [f"## {g}", table(["ID", "요구사항", "설명", "우선", "출처", "인수 기준"],
                [[r[0], r[2], r[3], r[4], SRCMAP[r[5]], r[6]] for r in rs]), ""]
    out += [read_src("req_appendix.md")]
    write("01_요구사항정의서.md", "\n".join(out))

# ───────────────────────── 03 기능정의서 ─────────────────────────
def doc03():
    out = ["# 03. 기능 정의서", HDR, f"총 **{len(FUNCS)}**개 기능. 각 기능은 요구사항(REQ)·화면(SCR)·API·프로그램(PGM)에 연결된다 (06 추적 매트릭스 참조).", ""]
    grp = collections.OrderedDict()
    for f in FUNCS: grp.setdefault(f[0].split("-")[1][:2], []).append(f)
    names = {"AU": "계정·인증", "PR": "출연자 프로필", "CO": "업체", "JB": "공고", "SR": "탐색", "AP": "지원", "SL": "선발", "OP": "합격 이후·현장",
             "NT": "알림", "BD": "게시판", "AD": "관리자", "SY": "시스템 횡단"}
    for k, fs in grp.items():
        out += [f"## {names[k]}", table(["ID", "기능", "설명", "수행", "우선", "요구사항", "화면", "API", "프로그램"],
                [[f[0], f[1], f[2], f[4], f[5], ", ".join(f[3]), ", ".join(f[6]) or "-", ", ".join(f[7]) or "-", ", ".join(f[8])] for f in fs]), ""]
    out += [read_src("func_appendix.md")]
    write("03_기능정의서.md", "\n".join(out))

# ───────────────────────── 04 메뉴구성도 ─────────────────────────
def doc04():
    plat = collections.OrderedDict([("APP", "출연자 앱 (Flutter) — 하단 탭: 홈 · 일 찾기 · 내 일정 · 메시지 · 내 정보"), ("FLD", "현장(반장) 모드 — 같은 앱 내 역할 전환"),
                                    ("WEB", "업체 웹 (React) — 좌측 사이드바"), ("ADM", "관리자 웹 (React) — 좌측 사이드바")])
    out = ["# 04. 메뉴 구성도", HDR, read_src("menu_intro.md")]
    for p, title in plat.items():
        out += [f"## {title}", "```text"]
        tree = collections.OrderedDict()
        for s in SCREENS:
            if s[1] != p: continue
            parts = s[2].split("/")
            node = tree
            for part in parts: node = node.setdefault(part, collections.OrderedDict())
            node.setdefault("__screens__", []).append(s)
        def walk(node, depth):
            for k, v in node.items():
                if k == "__screens__": continue
                out.append("  " * depth + ("├─ " if depth else "") + k)
                for s in v.get("__screens__", []):
                    out.append("  " * (depth + 1) + f"└ [{s[0]}] {s[3]}" + ("" if s[4] == "P1" else f"  ({s[4]})"))
                walk(v, depth + 1)
        walk(tree, 0)
        out += ["```", ""]
    write("04_메뉴구성도.md", "\n".join(out))

# ───────────────────────── 05 프로그램정의서 ─────────────────────────
def doc05():
    out = ["# 05. 프로그램 정의서", HDR, read_src("program_intro.md")]
    kinds = collections.OrderedDict()
    for p in PROGRAMS: kinds.setdefault(p[1], []).append(p)
    for k, ps in kinds.items():
        rows = []
        for p in ps:
            fs = [f[0] for f in FUNCS if p[0] in f[8]]
            rows.append([p[0], p[2], p[3], p[4], p[5], ", ".join(fs)])
        out += [f"## {k}", table(["ID", "이름", "위치", "책임", "실행/주기", "구현 기능"], rows), ""]
    out += [read_src("program_appendix.md")]
    write("05_프로그램정의서.md", "\n".join(out))

# ───────────────────────── 06 추적 매트릭스 ─────────────────────────
VERIFY = {"계정": "통합(Testcontainers)+보안", "출연자 프로필": "통합+수동", "업체": "통합(권한)", "공고": "통합+E2E", "탐색": "통합+성능", "지원": "동시성+통합",
          "선발": "동시성+통합+DB 제약", "합격 이후": "통합+배치+E2E", "알림": "통합+수동(실기기)", "게시판": "통합", "관리자": "통합+E2E",
          "수익화": "단위(게이트)", "비기능": "체크리스트/부하/점검"}
def doc06():
    out = ["# 06. 요구사항 추적 매트릭스 (RTM)", HDR, "요구사항 → 기능 → 화면 → API → 테이블 → 프로그램 → 검증 방법. 빈 칸이 없어야 한다(자동 검증됨).", ""]
    cov = f"- 요구사항 {len(REQS)}건 중 구현 기능이 연결된 건: **{len(used_req & set(rid))}**건 ({100*len(used_req & set(rid))//len(REQS)}%)\n"
    cov += f"- 기능 {len(FUNCS)}개 / 화면 {len(SCREENS)}개 / API {len(APIS)}개 / 프로그램 {len(PROGRAMS)}개 — 미연결 항목 0건\n"
    out += [cov]
    rows = []
    for r in REQS:
        fs = [f for f in FUNCS if r[0] in f[3]]
        scr = sorted({s for f in fs for s in f[6]}); api = sorted({a for f in fs for a in f[7]}); pgm = sorted({p for f in fs for p in f[8]})
        tbl = sorted({t for a in api for t in aid[a][6].split(",") if t})
        rows.append([r[0], r[2], r[4], ", ".join(f[0] for f in fs), ", ".join(scr) or "-", ", ".join(api) or "-", ", ".join(tbl) or "-", ", ".join(pgm), VERIFY[r[1]] + f" (TC-{r[0][4:]})"])
    out += [table(["REQ", "요구사항", "우선", "기능", "화면", "API", "테이블/뷰", "프로그램", "검증"], rows), ""]
    # 역방향: 화면/API 가 어떤 REQ 로 이어지는지
    out += ["## 역추적: API → 요구사항", table(["API", "요구사항"], [[a[0], ", ".join(sorted({x for f in FUNCS if a[0] in f[7] for x in f[3]}))] for a in APIS]), ""]
    write("06_요구사항추적매트릭스.md", "\n".join(out))

# ───────────────────────── 07 ERD/테이블정의서 ─────────────────────────
def doc07():
    out = ["# 07. ERD · 테이블 정의서", HDR, f"원천: `db/schema.sql` (PostgreSQL 16+). 테이블 **{len(TABLES)}**개, 뷰 **{len(VIEWS)}**개 ({', '.join('`'+v+'`' for v in VIEWS)}).", "", read_src("erd_intro.md")]
    out += ["## 1. 그룹별 ERD", ""]
    for g, ts in GROUPS.items():
        out += [f"### {g}", "```mermaid", "erDiagram"]
        tset = set(ts); ext = set()
        for t in ts:
            for col, ref in FKS.get(t, []):
                ref = ref.replace("public.", "")
                if t == ref: continue
                ext.add(ref) if ref not in tset else None
                out.append(f'  {ref} ||--o{{ {t} : "{col}"')
        for t in ts:
            out.append(f"  {t} {{")
            for c in TABLES[t][:6]:
                out.append(f"    {c['type'].split('(')[0].replace(' ', '_')} {c['name']}")
            out.append("  }")
        out += ["```", ""]
    out += ["## 2. 테이블 정의서", ""]
    for g, ts in GROUPS.items():
        out.append(f"### {g}")
        for t in ts:
            fkmap = {c: r.replace("public.", "") for c, r in FKS.get(t, [])}
            pkset = set(PK.get(t, []))
            out += [f"#### `{t}`" + (f" — {TABLE_COMMENT[t]}" if t in TABLE_COMMENT else ""), ""]
            rows = []
            for i, c in enumerate(TABLES[t], 1):
                key = []
                if c["name"] in pkset: key.append("PK")
                if c["name"] in fkmap: key.append("FK→" + fkmap[c["name"]])
                if c["name"] in UQ.get(t, set()): key.append("UQ")
                dflt = c["default"] if c["default"] and c["identity"] == "" else ("IDENTITY" if c["identity"] else "")
                rows.append([i, c["name"], c["type"].replace("character varying", "varchar").replace("timestamp with time zone", "timestamptz"), "N" if c["notnull"] else "Y", dflt, " ".join(key), COMMENTS.get((t, c["name"]), "")])
            out += [table(["#", "컬럼", "타입", "NULL", "기본값", "키", "설명"], rows), ""]
            ck = [f"- `{n}`: {(d if len(d) <= 220 else d[:217] + '…')}" for n, d in CHECKS.get(t, [])]
            ix = [f"- `{n}`: {d.split(' USING ')[-1] if ' USING ' in d else d}" for tb, n, d in IDX if tb == t and not n.endswith("_pkey")]
            if ck: out += ["제약(CHECK/EXCLUDE):", *ck, ""]
            if ix: out += ["인덱스:", *ix, ""]
    out += ["## 3. 뷰", ""]
    for v in VIEWS:
        out.append(f"- `{v}` — " + {"v_schedule_headcount_summary": "[인원표] 일자·역할별 정원/지원/합격/참석확인/출석/노쇼", "v_schedule_confirmed_roster": "[인원 정보표] 합격자 상세(백엔드 전용: 마스킹·복호화는 앱 계층)"}.get(v, ""))
    write("07_ERD_테이블정의서.md", "\n".join(out))

# ───────────────────────── 08 API 명세서 ─────────────────────────
def doc08():
    out = ["# 08. API 명세서", HDR, read_src("api_common.md")]
    names = {"AU": "인증·계정", "ME": "내 정보·프로필·설정", "FL": "파일", "CO": "업체", "JB": "공고 관리(업체)", "JS": "공고 탐색(공개)", "AP": "지원(출연자)",
             "SL": "선발(업체)", "OP": "합격 이후·현장", "NT": "알림", "BD": "게시판·신고", "AD": "관리자", "MS": "마스터"}
    grp = collections.OrderedDict()
    for a in APIS: grp.setdefault(a[0].split("-")[1][:2], []).append(a)
    out.append(f"총 **{len(APIS)}**개 엔드포인트. 권한 약어: PUB 비로그인 / USER 로그인 / ACTOR 출연자 / CMP 승인 업체 구성원 / CMP-M 업체 OWNER·MANAGER / CMP-F 업체 OWNER·MANAGER·담당 FIELD_LEADER / OPR 운영자 이상 / ADM 관리자.\n")
    for k, aps in grp.items():
        out += [f"## {names[k]}", table(["ID", "메서드", "경로", "이름", "권한", "요청/응답 요약", "테이블", "주요 오류", "구현 기능"],
                [[a[0], a[1], "`" + a[2] + "`", a[3], a[4], a[5], a[6], a[7], ", ".join(f[0] for f in FUNCS if a[0] in f[7])] for a in aps]), ""]
    out += [read_src("api_examples.md")]
    write("08_API명세서.md", "\n".join(out))

# ───────────────────────── 09 화면설계서 ─────────────────────────
def doc09():
    out = ["# 09. 화면 설계서", HDR, read_src("screen_intro.md"), "## 1. 화면 목록", ""]
    names = collections.OrderedDict([("APP", "출연자 앱"), ("FLD", "현장(반장) 모드"), ("WEB", "업체 웹"), ("ADM", "관리자 웹")])
    for p, nm in names.items():
        rows = []
        for s in SCREENS:
            if s[1] != p: continue
            fs = [f for f in FUNCS if s[0] in f[6]]
            apis = sorted({a for f in fs for a in f[7]})
            rows.append([s[0], s[2], s[3], s[4], s[5], ", ".join(f[0] for f in fs), ", ".join(apis) or "-"])
        out += [f"### {nm}", table(["ID", "메뉴", "화면", "우선", "구성/동작", "기능", "호출 API"], rows), ""]
    out += [read_src("wireframes.md")]
    write("09_화면설계서.md", "\n".join(out))

doc01(); doc03(); doc04(); doc05(); doc06(); doc07(); doc08(); doc09()

# ───────────────────────── 문서 간 참조 점검 ─────────────────────────
ALLOW_TOKENS = {"material_ui","cupertino_ui","qr_flutter","call_time","shoot_date","can_view_actor_applications","docker_compose","pg_trgm","btree_gist","pg_dump","pg_restore","cached_network_image","table_calendar","flutter_image_compress","flutter_secure_storage","flutter_local_notifications","firebase_messaging","mobile_scanner","url_launcher","image_picker","json_serializable","build_runner","openapi_typescript","browser_image_compression","react_hook_form","gen_random_uuid","pg_try_advisory_lock","cupertino_icons"}
known = set(ALL_SCHEMA_IDS) | {c["name"] for cols in TABLES.values() for c in cols} | SETTINGS_KEYS
FUT = ""
_fd = os.path.join(ROOT, "db", "future")
if os.path.isdir(_fd):
    for _f in os.listdir(_fd):
        FUT += open(os.path.join(_fd, _f), encoding="utf-8").read()
known |= set(re.findall(r"^\s+([a-z_0-9]+)\s+[A-Z]", FUT, re.M)) | set(re.findall(r"CREATE TABLE (\w+)", FUT))
known |= set(re.findall(r"(?:ux?|ix|uq|ex|ck|fk|trg)_[a-z_0-9]+", SCHEMA))
known |= set(re.findall(r"'([A-Z_]+)'", SCHEMA)) | set(re.findall(r"\('([a-z_A-Z]+)'", SCHEMA))
for fn in sorted(os.listdir(DOCS)):
    if not fn.endswith(".md") or fn[:2] in ("07",): continue
    text = open(os.path.join(DOCS, fn), encoding="utf-8").read()
    for tok in set(re.findall(r"`([a-z]+_[a-z_0-9]+)`", text)):
        if tok in known or tok.startswith(("app_", "web_")): continue
        if re.match(r"^(com|org)_", tok): continue
        if tok in ALLOW_TOKENS: continue
        warn(f"{fn}: 스키마에 없는 식별자 `{tok}`")


# ───────────────────────── 문서 내 ID 참조 점검 ─────────────────────────
idpat = re.compile(r"\b(REQ-[A-Z]+\d+|F-[A-Z]{2}\d+|SCR-[A-Z]+\d+|API-[A-Z]{2}\d+|PGM-[A-Z]\d+)\b")
valid = set(rid) | set(fid) | set(sid) | set(aid) | set(pid)
for fn in sorted(os.listdir(DOCS)):
    if not fn.endswith(".md"): continue
    for tok in set(idpat.findall(open(os.path.join(DOCS, fn), encoding="utf-8").read())):
        if tok not in valid: err(f"{fn}: 존재하지 않는 ID 참조 {tok}")

print(f"테이블 {len(TABLES)} / 뷰 {len(VIEWS)} / REQ {len(REQS)} / FUNC {len(FUNCS)} / SCR {len(SCREENS)} / API {len(APIS)} / PGM {len(PROGRAMS)}")
for w in warnings: print("WARN ", w)
for e in errors: print("ERROR", e)
print(f"결과: 오류 {len(errors)}건, 경고 {len(warnings)}건")
sys.exit(1 if errors else 0)
