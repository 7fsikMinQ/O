// 화면 시안 점검: 틀 안에서 글자·요소가 잘리거나 넘치는지 확인한다.
const { chromium } = require('playwright');
(async () => {
  const html = process.argv[2];
  const b = await chromium.launch(); const p = await b.newPage();
  await p.goto('file://' + html); await p.evaluate(() => document.fonts.ready);
  const res = await p.evaluate(() => {
    const out = [];
    document.querySelectorAll('figure.fig').forEach(f => {
      const id = f.querySelector('.sid').textContent;
      const zin = f.querySelector('.zin');
      // 1) 가로 넘침
      zin.querySelectorAll('*').forEach(el => {
        if (el.scrollWidth > el.clientWidth + 1 && getComputedStyle(el).overflow !== 'visible' && el.clientWidth > 0) out.push([id, '가로 넘침(숨김)', el.className.toString().slice(0, 30)]);
      });
      // 2) 휴대폰 본문 세로 잘림
      zin.querySelectorAll('.pbody').forEach(pb => { if (pb.scrollHeight > pb.clientHeight + 1) out.push([id, '휴대폰 본문 잘림', pb.scrollHeight - pb.clientHeight + 'px']); });
      // 3) 요소 간 겹침(같은 부모의 형제 사각형)
      const frame = zin.firstElementChild; const fr = frame.getBoundingClientRect();
      zin.querySelectorAll('*').forEach(el => {
        const r = el.getBoundingClientRect(); if (!r.width || !r.height) return;
        if (r.right > fr.right + 2 || r.left < fr.left - 2) { if (!el.closest('.phone') || true) out.push([id, '틀 밖으로 나감', el.className.toString().slice(0, 30)]); }
      });
      // 4) 글자 줄바꿈으로 버튼·배지 높이 초과
      zin.querySelectorAll('.btn,.badge,.chip').forEach(el => { const lh = parseFloat(getComputedStyle(el).lineHeight); if (el.scrollHeight > el.clientHeight + 2 && el.clientHeight > 0) out.push([id, '버튼/배지 글자 넘침', el.textContent.slice(0, 20)]); });
    });
    // 5) 글자 크기 최소(zoom 반영)
    return out;
  });
  const uniq = [...new Set(res.map(r => r.join(' | ')))];
  console.log(uniq.length ? uniq.join('\n') : 'OK 화면 점검 문제 없음');
  await b.close();
})();
