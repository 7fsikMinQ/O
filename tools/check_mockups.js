// 목업 자동 점검(겹침·잘림·글자·대비·터치). 사용: NODE_PATH=$(npm root -g) node tools/check_mockups.js [--shots]
// 앱 목업은 실제 폰처럼(?device=1) 320/360/390/412px 폭, 웹 목업은 768/1100/1440px 폭, 라이트·다크, 글씨 1배·1.3배로 점검한다.
const { chromium } = require('playwright'); const path = require('path');
const dir = path.resolve(__dirname, '../docs/mockups'); const shots = process.argv.includes('--shots');

function inPage(scale) {
  const lum = c => { const a = c.match(/[\d.]+/g).map(Number); const [r, g, b] = a.slice(0, 3).map(v => { v /= 255; return v <= .03928 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4 }); return .2126 * r + .7152 * g + .0722 * b };
  const bgOf = el => { while (el) { const c = getComputedStyle(el).backgroundColor; if (c && !c.endsWith(', 0)') && c !== 'transparent') return c; el = el.parentElement } return 'rgb(255,255,255)' };
  const label = el => { const s = el.closest('.phone,.web'); return s ? (s.getAttribute('aria-label') || '') : '' };
  const txt = el => ((el.textContent || '').trim().replace(/\s+/g, ' ')).slice(0, 18);
  const out = []; const add = (kind, el, extra = '') => out.push(`${kind} [${label(el)}] "${txt(el)}" ${extra}`);
  const els = [...document.querySelectorAll('body *')];
  if (scale !== 1) { const sizes = els.map(e => parseFloat(getComputedStyle(e).fontSize)); els.forEach((e, i) => e.style.fontSize = sizes[i] * scale + 'px'); }
  const isScroll = e => { const o = getComputedStyle(e); return /(auto|scroll|hidden)/.test(o.overflowX) };
  els.forEach(el => {
    const cs = getComputedStyle(el); const r = el.getBoundingClientRect(); if (!r.width || !r.height) return;
    const own = [...el.childNodes].filter(n => n.nodeType === 3 && n.textContent.trim());
    if (own.length) {
      const fs = parseFloat(cs.fontSize); const t = own.map(n => n.textContent.trim()).join('');
      if (/[\p{L}\p{N}]/u.test(t)) {   // 글자·숫자가 있을 때만 대비 검사(이모지는 자체 색)
        const l1 = lum(cs.color), l2 = lum(bgOf(el)), cr = (Math.max(l1, l2) + .05) / (Math.min(l1, l2) + .05);
        const big = fs >= 18.66 * scale || (fs >= 14 * scale && +cs.fontWeight >= 700);
        if (cr < (big ? 3 : 4.5)) add('대비', el, cr.toFixed(2));
      }
      if (scale === 1 && fs < 12) add('글자작음', el, fs + 'px');
      own.forEach(n => {                                                   // 텍스트가 자기 상자 밖으로 나가거나, 마지막 줄에 한 글자만 남는 경우
        const rg = document.createRange(); rg.selectNodeContents(n); const rects = [...rg.getClientRects()].filter(x => x.width > 0);
        if (!rects.length) return;
        const maxRight = Math.max(...rects.map(x => x.right)), minLeft = Math.min(...rects.map(x => x.left));
        const pad = parseFloat(cs.paddingRight) || 0;
        if (!isScroll(el) && (maxRight > r.right + 1 || minLeft < r.left - 1)) add('글자넘침', el, `${Math.round(maxRight - r.right)}px`);
        const lines = new Set(rects.map(x => Math.round(x.top))).size;
        if (n.textContent.trim().length >= 8 && lines > 1) { const last = rects.filter(x => Math.round(x.top) === Math.round(Math.max(...rects.map(y => y.top)))); const w = last.reduce((s, x) => s + x.width, 0); if (w < fs * 1.7) add('고아글자', el, `마지막 줄 ${Math.round(w)}px`); }
      });
    }
    if (el.tagName === 'BUTTON' && !(r.height >= 43.5 && r.width >= 43.5)) add('터치작음', el, `${Math.round(r.width)}x${Math.round(r.height)}`);
    const p = el.parentElement;
    if (p && p !== document.body && !isScroll(p) && !p.closest('.scroll')) {   // 자식이 부모 가로 범위를 벗어남
      const pr = p.getBoundingClientRect(); const pcs = getComputedStyle(p);
      if (pcs.display !== 'inline' && (r.right > pr.right + 1 || r.left < pr.left - 1) && cs.position !== 'absolute') add('부모밖', el, `${Math.round(Math.max(r.right - pr.right, pr.left - r.left))}px`);
    }
    if (!el.closest('.scroll') && p) {                                  // 형제끼리 겹침
      const sib = [...p.children].filter(s => s !== el && s.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING === 0);
      for (const s of [...p.children]) {
        if (s === el || (s.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_PRECEDING) === 0) continue;   // 쌍을 한 번만
        const sr = s.getBoundingClientRect(); if (!sr.width || !sr.height) continue;
        const ix = Math.min(r.right, sr.right) - Math.max(r.left, sr.left), iy = Math.min(r.bottom, sr.bottom) - Math.max(r.top, sr.top);
        if (ix > 2 && iy > 2 && getComputedStyle(s).position !== 'absolute' && cs.position !== 'absolute') add('겹침', el, `${Math.round(ix)}x${Math.round(iy)}`);
      }
    }
  });
  document.querySelectorAll('.phone').forEach(ph => {                    // 하단 고정 버튼이 본문을 가리지 않음, 폰 가로 넘침
    if (ph.scrollWidth > ph.clientWidth + 1) add('폰가로넘침', ph, `${ph.scrollWidth}>${ph.clientWidth}`);
    const c = ph.querySelector('.cta'); if (c) { const last = ph.querySelector('.body').lastElementChild; if (last && last.getBoundingClientRect().bottom > c.getBoundingClientRect().top + 1) add('버튼이본문가림', ph); }
  });
  if (document.documentElement.scrollWidth > innerWidth + 1) out.push(`문서가로넘침 ${document.documentElement.scrollWidth}>${innerWidth}`);
  return out;
}

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
