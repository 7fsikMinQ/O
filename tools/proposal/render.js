const { chromium } = require('playwright');
(async () => {
  const [html, pdf, mode] = process.argv.slice(2);
  const b = await chromium.launch();
  const p = await b.newPage();
  await p.goto('file://' + html);
  await p.evaluate(() => document.fonts.ready);
  await p.evaluate(() => {
    document.querySelectorAll('.zw,.zp').forEach(w => { const i = w.querySelector('.zin'); w.style.height = Math.ceil(i.offsetHeight * parseFloat(w.dataset.s)) + 'px'; });
  });
  const opts = { path: pdf, format: 'A4', printBackground: true, margin: { top: '16mm', bottom: '18mm', left: '16mm', right: '16mm' } };
  if (mode === 'body') {
    opts.displayHeaderFooter = true;
    opts.headerTemplate = '<div></div>';
    opts.footerTemplate = '<div style="font-size:8px;width:100%;padding:0 16mm;display:flex;justify-content:space-between;color:#5B6573;font-family:sans-serif"><span>OIO Casting Platform Proposal</span><span class="pageNumber"></span></div>';
  } else { opts.margin = { top: '16mm', bottom: '16mm', left: '16mm', right: '16mm' }; }
  await p.pdf(opts);
  await b.close();
})();
