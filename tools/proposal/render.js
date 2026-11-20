const { chromium } = require('playwright');
(async () => {
  const [html, pdf] = process.argv.slice(2);
  const b = await chromium.launch();
  const p = await b.newPage();
  await p.goto('file://' + html);
  await p.evaluate(() => document.fonts.ready);
  await p.evaluate(() => {
    document.querySelectorAll('.zw,.zp').forEach(w => { const i = w.querySelector('.zin'); w.style.height = Math.ceil(i.offsetHeight * parseFloat(w.dataset.s)) + 'px'; });
  });
  await p.pdf({ path: pdf, format: 'A4', printBackground: true, preferCSSPageSize: true });
  await b.close();
})();
