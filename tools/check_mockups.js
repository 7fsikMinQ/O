// 목업 자동 점검: 글자 대비(WCAG), 터치 대상(>=44px), 글자 크기(>=12px), 가로 넘침. 사용: node tools/check_mockups.js [--shots]
const { chromium } = require('playwright'); const path = require('path');
const dir = path.resolve(__dirname, '../docs/mockups'); const shots = process.argv.includes('--shots');
(async () => {
  const b = await chromium.launch(); let bad = 0;
  for (const f of ['app', 'app_dark', 'web', 'web_dark', 'web_notice', 'web_notice_dark']) for (const w of [(f.startsWith('app') ? 360 : 1100)]) {
    const p = await b.newPage({ viewport: { width: w, height: 800 } }); await p.goto('file://' + dir + '/' + f + '.html');
    const r = await p.evaluate(() => {
      const lum = c => { const a = c.match(/[\d.]+/g).map(Number); const [r, g, b] = a.slice(0, 3).map(v => { v /= 255; return v <= .03928 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4 }); return .2126 * r + .7152 * g + .0722 * b };
      const bgOf = el => { while (el) { const c = getComputedStyle(el).backgroundColor; if (c && !c.endsWith(', 0)') && c !== 'transparent') return c; el = el.parentElement } return 'rgb(255,255,255)' };
      const out = { contrast: [], small: [], target: [], overflow: document.documentElement.scrollWidth > innerWidth };
      document.querySelectorAll('body *').forEach(el => {
        const t = [...el.childNodes].filter(n => n.nodeType === 3 && n.textContent.trim()).map(n => n.textContent.trim()).join(''); if (!t) return;
        const cs = getComputedStyle(el), fg = cs.color, bg = bgOf(el), l1 = lum(fg), l2 = lum(bg), cr = (Math.max(l1, l2) + .05) / (Math.min(l1, l2) + .05);
        const big = parseFloat(cs.fontSize) >= 18.66 || (parseFloat(cs.fontSize) >= 14 && +cs.fontWeight >= 700);
        if (cr < (big ? 3 : 4.5)) out.contrast.push(t.slice(0, 20) + ' ' + cr.toFixed(2));
        if (parseFloat(cs.fontSize) < 14) out.small.push(t.slice(0, 20));
      });
      document.querySelectorAll('button').forEach(e => { const r = e.getBoundingClientRect(); if (r.height < 48) out.target.push(e.textContent.slice(0, 15) + ' ' + r.height) });
      return out;
    });
    const n = r.contrast.length + r.small.length + r.target.length + (r.overflow ? 1 : 0); bad += n;
    console.log(`${n ? '✘' : '✔'} ${f} @${w}px`, n ? JSON.stringify(r) : '');
    if (shots) await p.screenshot({ path: `${dir}/shot_${f}.png`, fullPage: true });
    await p.close();
  }
  // 글씨 1.3배(시스템 큰 글씨) 시: 각 요소 글자 크기를 1.3배로 키운 뒤 폰 프레임 안에서 가로로 넘치는 요소가 있는지
  const p = await b.newPage({ viewport: { width: 400, height: 800 } }); await p.goto('file://' + dir + '/app.html');
  const over = await p.evaluate(() => {
    const els = [...document.querySelectorAll('.phone *')]; const sizes = els.map(e => parseFloat(getComputedStyle(e).fontSize));
    els.forEach((e, i) => e.style.fontSize = sizes[i] * 1.3 + 'px'); const bad = [];
    document.querySelectorAll('.phone').forEach(ph => { const pr = ph.getBoundingClientRect(); ph.querySelectorAll('*').forEach(e => { const r = e.getBoundingClientRect(); if (r.width && r.right > pr.right + 1) bad.push((e.textContent || '').slice(0, 15)) }) });
    return bad;
  });
  if (over.length) { bad += over.length; console.log('✘ 글씨 1.3배에서 넘침:', JSON.stringify(over)); } else console.log('✔ 글씨 1.3배에서 폰 프레임 밖으로 넘치는 요소 없음');
  // 하단 고정 버튼이 본문을 가리는지(마지막 본문 요소 아래 끝 <= 버튼 위 끝), 글씨 1.0배·1.3배 모두
  for (const scale of [1, 1.3]) { const q = await b.newPage({ viewport: { width: 400, height: 800 } }); await q.goto('file://' + dir + '/app.html');
    const hid = await q.evaluate(sc => { const bad = []; document.querySelectorAll('.phone').forEach(ph => { const c = ph.querySelector('.cta'); if (!c) return;
      if (sc !== 1) ph.querySelectorAll('*').forEach(e => e.style.fontSize = parseFloat(getComputedStyle(e).fontSize) * sc + 'px');
      const last = ph.querySelector('.body').lastElementChild; if (last && last.getBoundingClientRect().bottom > c.getBoundingClientRect().top + 1) bad.push(ph.getAttribute('aria-label')) }); return bad }, scale);
    if (hid.length) { bad += hid.length; console.log(`✘ 하단 버튼이 본문을 가림 (글씨 ${scale}배):`, hid.join(', ')); } else console.log(`✔ 하단 버튼이 본문을 가리지 않음 (글씨 ${scale}배)`); await q.close(); }
  await b.close(); process.exit(bad ? 1 : 0);
})();
