// 화면 자동 점검(겹침·잘림·글자·대비·터치·고아 글자). 목업(check_mockups.js)과 실제 화면(e2e/web_flow.mjs)이 함께 쓴다.
// page.evaluate(inPage, {scale, tag}) 로 호출하면 문제 문자열 배열을 돌려준다.
function inPage(arg) {
  const scale = typeof arg === 'number' ? arg : (arg && arg.scale) || 1; const tag = (arg && arg.tag) || '';
  const lum = c => { const a = c.match(/[\d.]+/g).map(Number); const [r, g, b] = a.slice(0, 3).map(v => { v /= 255; return v <= .03928 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4 }); return .2126 * r + .7152 * g + .0722 * b };
  const bgOf = el => { while (el) { const c = getComputedStyle(el).backgroundColor; if (c && !c.endsWith(', 0)') && c !== 'transparent') return c; el = el.parentElement } return 'rgb(255,255,255)' };
  const label = el => { const s = el.closest('.phone,.web'); return s ? (s.getAttribute('aria-label') || '') : tag };
  const txt = el => ((el.textContent || '').trim().replace(/\s+/g, ' ')).slice(0, 18);
  const out = []; const add = (kind, el, extra = '') => out.push(`${kind} [${label(el)}] "${txt(el)}" ${extra}`);
  const els = [...document.querySelectorAll('body *')];
  const orig = els.map(e => e.style.fontSize);   // 글씨 배율 점검 뒤 원래 크기로 되돌린다(폼 입력 상태를 지우지 않도록 새로고침하지 않는다)
  if (scale !== 1) { const sizes = els.map(e => parseFloat(getComputedStyle(e).fontSize)); els.forEach((e, i) => e.style.fontSize = sizes[i] * scale + 'px'); }
  const isScroll = e => { const o = getComputedStyle(e); return /(auto|scroll|hidden)/.test(o.overflowX) };
  const floating = e => { for (let x = e; x && x !== document.body; x = x.parentElement) { const ps = getComputedStyle(x).position; if (ps === 'fixed' || ps === 'sticky') return true; } return false; };
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
      if (pcs.display !== 'inline' && (r.right > pr.right + 1 || r.left < pr.left - 1) && cs.position !== 'absolute' && !floating(el)) add('부모밖', el, `${Math.round(Math.max(r.right - pr.right, pr.left - r.left))}px`);
    }
    if (!el.closest('.scroll') && p && !floating(el)) {                                  // 형제끼리 겹침
      const sib = [...p.children].filter(s => s !== el && s.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING === 0);
      for (const s of [...p.children]) {
        if (s === el || (s.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_PRECEDING) === 0) continue;   // 쌍을 한 번만
        const sr = s.getBoundingClientRect(); if (!sr.width || !sr.height) continue;
        const ix = Math.min(r.right, sr.right) - Math.max(r.left, sr.left), iy = Math.min(r.bottom, sr.bottom) - Math.max(r.top, sr.top);
        if (ix > 2 && iy > 2 && getComputedStyle(s).position !== 'absolute' && cs.position !== 'absolute' && !floating(s)) add('겹침', el, `${Math.round(ix)}x${Math.round(iy)}`);
      }
    }
  });
  document.querySelectorAll('.phone').forEach(ph => {                    // 하단 고정 버튼이 본문을 가리지 않음, 폰 가로 넘침
    if (ph.scrollWidth > ph.clientWidth + 1) add('폰가로넘침', ph, `${ph.scrollWidth}>${ph.clientWidth}`);
    const c = ph.querySelector('.cta'); if (c) { const last = ph.querySelector('.body').lastElementChild; if (last && last.getBoundingClientRect().bottom > c.getBoundingClientRect().top + 1) add('버튼이본문가림', ph); }
  });
  if (document.documentElement.scrollWidth > innerWidth + 1) out.push(`문서가로넘침 ${document.documentElement.scrollWidth}>${innerWidth}`);
  els.forEach((e, i) => { e.style.fontSize = orig[i]; });
  return out;
}

module.exports = { inPage };
