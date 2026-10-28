// 업체·관리자 웹 전체 흐름 시험(실제 백엔드 + 실제 브라우저) + 화면 겹침·잘림 점검 + 스크린샷.
// 준비: 백엔드(8080)·웹(5173, vite dev)·메일함이 떠 있어야 한다. 메일은 Mailpit(http://localhost:8025) 또는 MAIL_DIR(파일로 받는 시험용 SMTP)에서 읽는다.
// 사용: NODE_PATH=$(npm root -g) node tools/e2e/web_flow.mjs [--shots=docs/screens]
import { createRequire } from 'module';
import fs from 'fs';
import path from 'path';
import zlib from 'zlib';
const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const { inPage } = require('../ui_audit.js');

const WEB = process.env.WEB_URL ?? 'http://127.0.0.1:5173';
const API = process.env.API_URL ?? 'http://127.0.0.1:8080';
const MAILPIT = process.env.MAILPIT_URL ?? 'http://127.0.0.1:8025';
const MAIL_DIR = process.env.MAIL_DIR;
const SHOTS = (process.argv.find((a) => a.startsWith('--shots=')) ?? '').slice(8);
const stamp = Date.now().toString(36);
if (SHOTS) fs.mkdirSync(SHOTS, { recursive: true });

let failed = 0;
const problems = [];
const ok = (m) => console.log(`  ✔ ${m}`);
const must = (cond, m) => { if (cond) ok(m); else { failed++; console.log(`  ✘ ${m}`); } };

// ---------- API 도우미 ----------
async function api(method, p, body, token) {
  const headers = {};
  let payload;
  if (body instanceof FormData) payload = body;
  else if (body !== undefined) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(API + p, { method, headers, body: payload });
  const text = await res.text();
  let json; try { json = text ? JSON.parse(text) : undefined; } catch { json = text; }
  return { status: res.status, json };
}
const must2xx = (r, what) => { if (r.status >= 300) throw new Error(`${what} 실패: ${r.status} ${JSON.stringify(r.json)}`); return r.json; };

/** 파일로 받은 원본 메일에서 본문(base64 또는 quoted-printable)과 제목을 풀어 평문으로 만든다. */
function decodeMail(raw) {
  const [head, ...rest] = raw.split(/\r?\n\r?\n/);
  const body = rest.join('\n\n');
  let text = body;
  if (/Content-Transfer-Encoding: base64/i.test(head)) text = Buffer.from(body.replace(/\s+/g, ''), 'base64').toString('utf8');
  else if (/quoted-printable/i.test(head)) text = body.replace(/=\r?\n/g, '').replace(/=([0-9A-F]{2})/g, (_, h) => String.fromCharCode(parseInt(h, 16)));
  const subj = /Subject: ((?:.|\n )+)/.exec(head)?.[1] ?? '';
  const subject = subj.replace(/\n /g, '').replace(/=\?UTF-8\?Q\?([^?]*)\?=/g, (_, q) => Buffer.from(q.replace(/_/g, ' ').replace(/=([0-9A-F]{2})/g, (m, h) => String.fromCharCode(parseInt(h, 16))), 'latin1').toString('utf8'));
  return `${head.split('\n')[0]}\n${subject}\n${text}`;
}

async function latestMail(to, match) {
  for (let i = 0; i < 40; i++) {
    if (MAIL_DIR) {
      const files = fs.readdirSync(MAIL_DIR).sort().reverse();
      for (const f of files) { const t = decodeMail(fs.readFileSync(path.join(MAIL_DIR, f), 'utf8')); if (t.startsWith(`RCPT: ${to}`) && (!match || match.test(t))) return t; }
    } else {
      try {
        const list = await (await fetch(`${MAILPIT}/api/v1/search?query=to:${encodeURIComponent(to)}`)).json();
        for (const m of list.messages ?? []) { const full = await (await fetch(`${MAILPIT}/api/v1/message/${m.ID}`)).json(); const t = `${full.Subject}\n${full.Text}`; if (!match || match.test(t)) return t; }
      } catch { /* 메일함 준비 전 */ }
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`메일을 못 받았어요: ${to}`);
}

function png(w, h, fn) {
  const raw = Buffer.alloc((w * 3 + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (w * 3 + 1)] = 0; for (let x = 0; x < w; x++) { const [r, g, b] = fn(x, y); const o = y * (w * 3 + 1) + 1 + x * 3; raw[o] = r; raw[o + 1] = g; raw[o + 2] = b; } }
  const crcT = []; for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; crcT[n] = c >>> 0; }
  const crc = (buf) => { let c = 0xffffffff; for (const b of buf) c = crcT[(c ^ b) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const chunk = (type, data) => { const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([len, td, c]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}
const photo = () => png(400, 500, (x, y) => [180 + (x % 40), 160 + (y % 50), 220]);
const signature = () => png(300, 120, (x, y) => (Math.abs(y - 60 - Math.sin(x / 15) * 25) < 3 ? [20, 20, 40] : [255, 255, 255]));

async function termIds(types) { const t = await api('GET', '/terms'); return t.json.filter((x) => types.includes(x.type)).map((x) => x.id); }

/** 출연자(이메일 코드 채널): 가입 → 프로필(연락처 포함) → 주민번호 → 계좌. */
let serial = parseInt(stamp, 36) % 100000;
async function newActor(n, name) {
  const email = `actor${n}-${stamp}@example.test`;
  const cfg = (await api('GET', '/app/config?platform=web&version=1.0.0')).json;
  if (cfg.otpChannel !== 'email') throw new Error('이 시험은 이메일 코드 채널(otp-channel=email)에서 돈다');
  must2xx(await api('POST', '/auth/otp/send', { email }), '인증번호 요청');
  const code = /인증번호: (\d{6})/.exec(await latestMail(email, /인증번호/))[1];
  const v = must2xx(await api('POST', '/auth/otp/verify', { email, code }), '인증번호 확인');
  const signed = must2xx(await api('POST', '/auth/signup', { signupToken: v.signupToken, name, agreedTermIds: await termIds(['SERVICE', 'PRIVACY', 'THIRD_PARTY_PROVISION']) }), '출연자 가입');
  const token = signed.accessToken;
  const phone = `010${String(40000000 + (serial * 7 + n) % 9999999).padStart(8, '0')}`;
  must2xx(await api('PUT', '/me/actor-profile', { birthDate: '1990-01-01', gender: 'MALE', heightCm: 170 + n, weightKg: 65 + n, topSize: '100', bottomSize: '32', shoeSize: 270, hairLength: 'SHORT', hasTattoo: false, homeAddress: '서울 강남구 테헤란로 1', defaultDepartureLocation: '강남역 1번 출구', phone }, token), '프로필');
  const rrn = `900101-1${String(serial * 13 + n * 7919).padStart(6, '0').slice(-6)}`;
  must2xx(await api('PUT', '/me/actor-profile/tax-identity', { rrn, termVersion: '0.1' }, token), '주민번호');
  must2xx(await api('PUT', '/me/actor-profile/payout', { bankCode: '004', accountNumber: `1101234${String(serial + n).padStart(5, '0').slice(-5)}`, holder: name }, token), '계좌');
  const me = (await api('GET', '/me', undefined, token)).json;
  return { token, id: me.id, name, email, phone };
}

async function applyAs(actor, positionId) {
  const up = async (kind, buf) => { const f = new FormData(); f.append('file', new Blob([buf], { type: 'image/png' }), `${kind}.png`); f.append('kind', kind); return must2xx(await api('POST', '/me/apply-files', f, actor.token), `${kind} 업로드`).fileId; };
  const photoId = await up('PHOTO', photo());
  const sigId = await up('SIGNATURE', signature());
  return must2xx(await api('POST', `/positions/${positionId}/applications`, { photoFileId: photoId, signatureFileId: sigId, agreeESignature: true, useProfileAccount: true, message: '열심히 하겠습니다' }, actor.token), '지원').id;
}

// ---------- 화면 점검 ----------
const SIZES = [[1440, 900], [768, 1000], [390, 844]];
async function audit(page, name) {
  const saved = page.viewportSize();
  const found = [];
  for (const [w, h] of SIZES) {
    await page.setViewportSize({ width: w, height: h });
    await page.waitForTimeout(150);
    for (const scale of [1, 1.3]) {
      const issues = [...new Set(await page.evaluate(inPage, { scale, tag: name }))].filter((i) => !/^대비 .*(select|option)/.test(i));
      issues.forEach((i) => found.push(`@${w}px x${scale}: ${i}`));
    }
  }
  await page.setViewportSize(saved);
  if (found.length) { problems.push(...found.map((f) => `[${name}] ${f}`)); console.log(`  ✘ 화면 점검 ${name}: ${found.length}건`); found.slice(0, 6).forEach((f) => console.log(`      ${f}`)); failed++; }
  else ok(`화면 점검 ${name}: 겹침·잘림·대비·터치 이상 없음 (1440/768/390px, 글씨 1배·1.3배)`);
}
async function shot(page, name) { if (SHOTS) await page.screenshot({ path: path.join(SHOTS, `${name}.png`), fullPage: true }); }
const wait = (page) => page.waitForLoadState('networkidle');

// ---------- 시나리오 ----------
(async () => {
  const browser = await chromium.launch();
  const ownerEmail = `owner-${stamp}@example.test`;
  const pw = 'Passw0rd-1234';
  const biz = (() => { const f = '30' + stamp.replace(/\D/g, '').padEnd(7, '1').slice(0, 7); const w = [1, 3, 7, 1, 3, 7, 1, 3, 5]; let s = 0; for (let i = 0; i < 9; i++) s += +f[i] * w[i]; s += Math.floor((+f[8] * 5) / 10); return f + ((10 - (s % 10)) % 10); })();
  try {
    console.log('1. 출연자 3명 준비(이메일 코드 가입)');
    const actors = [await newActor(1, '김하나'), await newActor(2, '이둘'), await newActor(3, '박셋')];
    ok('출연자 가입·프로필·주민번호·계좌 완료');

    console.log('2. 업체 가입 → 이메일 확인 → 업체 등록 → 관리자 승인');
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ko-KR' });
    const page = await ctx.newPage();
    page.on('dialog', (d) => d.accept(d.type() === 'prompt' ? '확인 사유: 시험' : undefined));
    await page.goto(`${WEB}/login`); await wait(page);
    await shot(page, 'web_01_login'); await audit(page, '로그인');
    await page.getByRole('link', { name: '업체 가입하기' }).click();
    await page.getByLabel('이름').fill('시험 대표'); await page.getByLabel('이메일').fill(ownerEmail); await page.getByLabel('비밀번호').fill(pw);
    await page.getByRole('checkbox').check(); await shot(page, 'web_02_signup'); await audit(page, '업체 가입');
    await page.getByRole('button', { name: '가입하기' }).click();
    await page.waitForURL('**/verify-sent'); ok('가입 → 이메일 확인 안내 화면'); await shot(page, 'web_03_verify_sent'); await audit(page, '이메일 확인 안내');
    const mail = await latestMail(ownerEmail, /verify-email/);
    const token = /token=([A-Za-z0-9_-]+)/.exec(mail)[1];
    await page.goto(`${WEB}/verify-email?token=${token}`); await page.getByRole('button', { name: '이메일 확인하기' }).click();
    await page.getByText('확인이 끝났어요').waitFor(); ok('메일 링크로 이메일 확인 완료'); await shot(page, 'web_04_verified');
    await page.goto(`${WEB}/`); await wait(page);
    must(await page.getByRole('heading', { name: /업체 등록/ }).isVisible(), '확인 뒤 업체 등록 화면'); await audit(page, '업체 등록');
    await page.getByLabel('상호').fill(`시험기획 ${stamp}`); await page.getByLabel('사업자등록번호').fill(biz); await page.getByLabel('대표자').fill('시험 대표');
    await page.getByLabel('전화').fill('02-123-4567'); await page.getByLabel('주소').fill('서울 마포구');
    fs.writeFileSync('/tmp/license.pdf', '%PDF-1.4\n% 시험 사업자등록증\n');
    await page.locator('input[type=file]').setInputFiles('/tmp/license.pdf');
    await page.getByRole('button', { name: '등록 신청' }).click();
    await page.getByRole('heading', { name: /승인을 기다리고/ }).waitFor(); ok('등록 신청 → 승인 대기 화면'); await shot(page, 'web_05_pending'); await audit(page, '승인 대기');

    const adminCtx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ko-KR' });
    const admin = await adminCtx.newPage();
    await admin.goto(`${WEB}/login`);
    await admin.getByLabel(/이메일/).fill('admin'); await admin.getByLabel('비밀번호').fill('admin');
    await admin.getByRole('button', { name: '로그인' }).click();
    await admin.getByLabel(/2차 비밀번호/).waitFor(); ok('관리자는 1차 비밀번호 뒤에만 2차 칸이 나타남');
    await shot(admin, 'web_06_admin_second'); await admin.getByLabel(/2차 비밀번호/).fill('admin1234'); await admin.getByRole('button', { name: '로그인' }).click();
    await admin.waitForURL('**/admin'); await wait(admin); ok('관리자 로그인(admin/admin + 2차 admin1234)');
    await admin.getByRole('button', { name: '검토' }).first().click();
    await admin.getByRole('dialog').waitFor(); await shot(admin, 'web_07_admin_review');
    await admin.getByRole('button', { name: '승인', exact: true }).click(); await admin.getByText('승인했어요').waitFor(); ok('관리자가 업체 승인');
    await shot(admin, 'web_08_admin'); await audit(admin, '관리자');
    for (const t of ['회원', '공고', '신고', '설정·최저시급', '기능 스위치', '감사 로그', '통계']) { await admin.getByRole('tab', { name: t }).click(); await wait(admin); }
    await admin.getByRole('tab', { name: '기능 스위치' }).click(); await shot(admin, 'web_09_admin_flags'); await audit(admin, '관리자 기능 스위치');
    await adminCtx.close();

    console.log('3. 공고 작성·게시');
    await page.goto(`${WEB}/`); await wait(page);
    must(await page.getByRole('heading', { name: /안녕하세요/ }).isVisible(), '승인 뒤 대시보드가 열림'); await shot(page, 'web_10_home'); await audit(page, '대시보드');
    await page.getByRole('link', { name: '📋 공고' }).click(); await wait(page);
    await page.getByRole('link', { name: '➕ 새 공고' }).click(); await wait(page);
    const day = new Date(Date.now() + 5 * 86400e3 + 9 * 3600e3).toISOString().slice(0, 10);
    await page.getByLabel('제목 (가제로 써도 돼요)').fill(`가제 사극 ${stamp}`);
    await page.getByLabel('설명').fill('저잣거리 행인 역할입니다.');
    await page.getByLabel('집합 시각 (예상)').fill(`${day}T09:00`); await page.getByLabel(/종료 \(예상\)/).fill(`${day}T17:00`);
    await page.getByLabel('지역').selectOption({ label: '서울특별시' });
    await page.getByLabel('촬영지 (비공개)').fill('비밀 세트장'); await page.getByLabel('집합 장소').fill('강남역 1번 출구'); await page.getByLabel(/집합 주소/).fill('서울 강남구 강남대로 396');
    await page.getByLabel('역할 이름').fill('저잣거리 행인'); await page.getByLabel('모집 인원').fill('2');
    await page.getByLabel('성별').selectOption('MALE');
    await shot(page, 'web_11_job_editor'); await audit(page, '공고 작성');
    await page.getByRole('button', { name: '게시하기' }).click();
    await page.waitForURL(/\/jobs\/\d+$/); await wait(page); ok('공고 게시');
    const jobId = +page.url().match(/jobs\/(\d+)/)[1];
    must(await page.getByText('예상이에요').isVisible() || (await page.content()).includes('예상'), '시간은 예상이라는 안내가 보임');
    await shot(page, 'web_12_job_detail'); await audit(page, '공고 상세');
    const list = (await api('GET', `/jobs?q=${encodeURIComponent('가제 사극 ' + stamp)}`)).json;
    must(list.items.length === 1, '출연자 목록(비로그인)에 공고가 보임');
    const detail = (await api('GET', `/jobs/${jobId}`)).json;
    must(detail.detailsHidden === true && detail.schedules[0].shootingLocation === null, '비공개 현장: 촬영지·집합 주소 숨김');
    const positionId = detail.schedules[0].positions[0].id;
    const scheduleId = detail.schedules[0].id;

    console.log('4. 출연자 지원 → 업체가 보고 합격 통보 → 출연자 확정');
    const appIds = [];
    for (const a of actors) appIds.push(await applyAs(a, positionId));
    ok('출연자 3명이 사진·서명과 함께 지원');
    await page.reload(); await wait(page);
    await page.getByRole('tab', { name: /지원/ }).first().click();
    await shot(page, 'web_13_applicants'); await audit(page, '지원자 목록');
    await page.getByRole('row', { name: /김하나/ }).click(); await page.getByRole('dialog').getByText('지원 역할').waitFor();
    must(await page.getByRole('dialog').getByText(/900101-1\*{6}/).isVisible(), '주민번호는 가려서 보임');
    must(!(await page.getByRole('dialog').innerText()).includes('테헤란로'), '합격 통보 전에는 집 주소가 보이지 않음');
    await shot(page, 'web_14_applicant_drawer'); await audit(page, '지원자 상세');
    await page.getByRole('button', { name: '전체 보기' }).click(); await page.getByRole('dialog').getByText(/900101-1\d{6}/).waitFor(); ok('주민번호 전체 열람(사유 입력, 기록 남음)');
    await page.getByRole('button', { name: '닫기' }).first().click();
    await page.getByLabel('김하나 선택').check(); await page.getByLabel('이둘 선택').check(); await page.getByLabel('박셋 선택').check();
    await page.getByRole('button', { name: /합격 통보/ }).click(); await page.getByText(/명 처리, 1명은 못 했어요/).waitFor(); ok('정원 2명 → 3명 선택 시 1명은 정원 초과로 못 뽑음');
    await shot(page, 'web_15_selected');
    const mine = (await api('GET', '/me/applications', undefined, actors[0].token)).json;
    must(mine[0].status === 'SELECTED', '출연자에게 합격 통보 도착(SELECTED)');
    must2xx(await api('POST', `/applications/${appIds[0]}/confirm`, {}, actors[0].token), '확정 1');
    must2xx(await api('POST', `/applications/${appIds[1]}/confirm`, {}, actors[1].token), '확정 2');
    await page.reload(); await wait(page);
    await page.getByRole('tab', { name: /확정/ }).click(); await shot(page, 'web_16_confirmed'); await audit(page, '확정 인원');

    console.log('5. 인원 정보표 · 단체 알림 · 메시지');
    await page.goto(`${WEB}/schedules/${scheduleId}`); await wait(page);
    must(await page.getByText('****').first().isVisible() || (await page.content()).includes('****'), '인원 정보표: 계좌는 끝 4자리만');
    must(!(await page.content()).includes('9001011'), '인원 정보표에 주민번호 전체가 없음');
    await shot(page, 'web_17_schedule'); await audit(page, '인원표·현장');
    await page.goto(`${WEB}/broadcasts`); await wait(page);
    await page.getByLabel('공고').selectOption({ index: 1 }); await page.getByLabel('제목').fill('집합 장소 안내'); await page.getByLabel('내용').fill('강남역 1번 출구에서 8시 50분까지 모여 주세요.');
    await page.getByRole('button', { name: '보내기' }).click(); await page.getByText('명에게 보냈어요').waitFor(); ok('확정자 전체에게 단체 알림');
    await page.getByRole('button', { name: '읽음 현황' }).first().click(); await page.getByText('아직 안 읽음').first().waitFor();
    await shot(page, 'web_18_broadcast'); await audit(page, '단체 알림');
    const threads = (await api('GET', '/me/messages/threads', undefined, actors[0].token)).json;
    must2xx(await api('GET', `/me/messages/threads/${threads[0].id}`, undefined, actors[0].token), '출연자가 공지를 열어 읽음 처리');
    await page.getByRole('button', { name: '안 읽은 사람에게 다시 알리기' }).click(); await page.getByText(/다시 알렸어요/).waitFor(); ok('안 읽은 사람에게만 다시 알림');
    must2xx(await api('POST', `/me/messages/threads/${threads[0].id}/reply`, { body: '네, 확인했어요!' }, actors[0].token), '출연자 답장');
    await page.goto(`${WEB}/messages`); await wait(page);
    await page.getByRole('button', { name: /김하나/ }).first().click(); await page.getByText('네, 확인했어요!').waitFor(); ok('업체가 출연자 답장을 봄');
    await shot(page, 'web_19_messages'); await audit(page, '메시지');

    console.log('6. 현장 QR 출퇴근(오늘 일정)');
    const login = must2xx(await api('POST', '/auth/email/login', { email: ownerEmail, password: pw }), '업체 로그인');
    const soon = new Date(Date.now() + 90 * 60e3);
    const todayJob = must2xx(await api('POST', '/company/jobs', { title: `당일 촬영 ${stamp}`, schedules: [{ startAt: soon.toISOString(), endAt: new Date(soon.getTime() + 8 * 3600e3).toISOString(), regionId: 1, meetingPlace: '정문', positions: [{ title: '행인', headcount: 1, wageMode: 'MIN_WAGE' }] }] }, login.accessToken), '당일 공고');
    must2xx(await api('POST', `/company/jobs/${todayJob.id}/publish`, {}, login.accessToken), '게시');
    const tj = (await api('GET', `/company/jobs/${todayJob.id}`, undefined, login.accessToken)).json;
    const todayApp = await applyAs(actors[2], tj.schedules[0].positions[0].id);
    must2xx(await api('POST', '/company/applications/decide', { applicationIds: [todayApp], decision: 'SELECT' }, login.accessToken), '합격 통보');
    must2xx(await api('POST', `/applications/${todayApp}/confirm`, {}, actors[2].token), '확정');
    await page.goto(`${WEB}/schedules/${tj.schedules[0].id}`); await wait(page);
    await page.getByRole('button', { name: '출근 열기' }).click(); await page.getByText('출근 열림').waitFor(); ok('업체 구성원이 출근 열기');
    const qr = must2xx(await api('GET', `/me/applications/${todayApp}/qr`, undefined, actors[2].token), 'QR 토큰');
    await page.getByLabel('QR 코드 직접 입력').fill(qr.token); await page.getByRole('button', { name: '기록' }).click();
    await page.getByText(/박셋 · 행인/).waitFor(); ok('QR 스캔 → 출근 기록');
    await shot(page, 'web_20_attendance'); await audit(page, '출퇴근(QR)');

    console.log('7. 직원·⭐·취소 판정 화면');
    for (const [route, name, shotName] of [['/members', '직원 관리', 'web_21_members'], ['/stars', '⭐ 출연자', 'web_22_stars'], ['/reviews', '취소 판정', 'web_23_reviews'], ['/jobs', '공고 목록', 'web_24_jobs']]) {
      await page.goto(`${WEB}${route}`); await wait(page); await shot(page, shotName); await audit(page, name);
    }
    await ctx.close();
  } catch (e) {
    failed++; console.log(`  ✘ 중단: ${e.message}`);
  }
  await browser.close();
  console.log(failed ? `\n실패 ${failed}건` : '\n웹 전체 흐름 통과');
  if (problems.length) fs.writeFileSync('/tmp/e2e/web_problems.txt', problems.join('\n'));
  process.exit(failed ? 1 : 0);
})();
