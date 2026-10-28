// 목업 자동 점검: 글자 대비(WCAG), 터치 대상(>=44px), 글자 크기(>=12px), 가로 넘침. 사용: node tools/check_mockups.js [--shots]
const { chromium } = require('playwright'); const path = require('path');
const dir = path.resolve(__dirname, '../docs/mockups'); const shots = process.argv.includes('--shots');
(async () => {
  const b = await chromium.launch(); let bad = 0;
  for (const f of ['app', 'app_dark', 'web', 'web_dark']) for (const w of [(f.startsWith('app') ? 360 : 1100)]) {
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
        if (parseFloat(cs.fontSize) < 12) out.small.push(t.slice(0, 20));
      });
      document.querySelectorAll('button').forEach(e => { const r = e.getBoundingClientRect(); if (r.height < 44) out.target.push(e.textContent.slice(0, 15) + ' ' + r.height) });
      return out;
    });
    const n = r.contrast.length + r.small.length + r.target.length + (r.overflow ? 1 : 0); bad += n;
    console.log(`${n ? '✘' : '✔'} ${f} @${w}px`, n ? JSON.stringify(r) : '');
    if (shots) await p.screenshot({ path: `${dir}/shot_${f}.png`, fullPage: true });
    await p.close();
  }
  // 작은 화면 360 에서 phone 카드가 넘치지 않는지 / 큰 글씨(1.3배) 시 가로 넘침
  const p = await b.newPage({ viewport: { width: 360, height: 640 } }); await p.goto('file://' + dir + '/app.html');
  await p.addStyleTag({ content: 'html{font-size:20.8px} .phone{width:auto;max-width:100%}' });
  const o = await p.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1); console.log(o ? '✘ 360px 큰 글씨 가로 넘침(목업 카드 폭 고정 때문, 실제 앱은 반응형)' : '✔ 큰 글씨 가로 넘침 없음');
  await b.close(); process.exit(bad ? 1 : 0);
})();
