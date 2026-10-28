// 목업 자동 점검(겹침·잘림·글자·대비·터치). 사용: NODE_PATH=$(npm root -g) node tools/check_mockups.js [--shots]
// 앱 목업은 실제 폰처럼(?device=1) 320/360/390/412px 폭, 웹 목업은 768/1100/1440px 폭, 라이트·다크, 글씨 1배·1.3배로 점검한다.
const { chromium } = require('playwright'); const path = require('path');
const dir = path.resolve(__dirname, '../docs/mockups'); const shots = process.argv.includes('--shots');

const { inPage } = require('./ui_audit');

(async () => {
  const b = await chromium.launch(); let bad = 0; const summary = [];
  const jobs = [];
  for (const f of ['app', 'app_dark']) for (const w of [320, 360, 390, 412]) jobs.push({ f, w, q: '?device=1' });
  for (const f of ['web', 'web_dark']) for (const w of [768, 1100, 1440]) jobs.push({ f, w, q: '' });
  for (const j of jobs) for (const scale of [1, 1.3]) {
    const p = await b.newPage({ viewport: { width: j.w, height: 900 } }); await p.goto('file://' + dir + '/' + j.f + '.html' + j.q);
    const issues = [...new Set(await p.evaluate(inPage, scale))]; bad += issues.length;
    summary.push(`${issues.length ? '✘' : '✔'} ${j.f} @${j.w}px x${scale} ${issues.length ? '(' + issues.length + '건)' : ''}`);
    if (issues.length) issues.slice(0, 12).forEach(i => summary.push('    ' + i));
    await p.close();
  }
  console.log(summary.join('\n'));
  if (shots) {
    for (const [f, w] of [['app', 1180], ['app_dark', 1180], ['web', 1100], ['web_dark', 1100]]) {
      const p = await b.newPage({ viewport: { width: w, height: 900 } }); await p.goto('file://' + dir + '/' + f + '.html'); await p.screenshot({ path: `${dir}/shot_${f}.png`, fullPage: true }); await p.close();
    }
  }
  await b.close(); console.log(bad ? `\n총 ${bad}건` : '\n전부 통과'); process.exit(bad ? 1 : 0);
})();
