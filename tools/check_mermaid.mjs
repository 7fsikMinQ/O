import { JSDOM } from 'jsdom';
import fs from 'fs';
const dom = new JSDOM('<!DOCTYPE html><body></body>');
globalThis.window = dom.window; globalThis.document = dom.window.document;
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});
const mermaid = (await import('mermaid')).default;
mermaid.initialize({startOnLoad:false});
let total=0, bad=0;
for (const f of process.argv.slice(2)) {
  const t = fs.readFileSync(f,'utf8');
  const re = /```mermaid\n([\s\S]*?)```/g; let m; let i=0;
  while ((m = re.exec(t))) { i++; total++;
    try { await mermaid.parse(m[1]); } catch(e) { bad++; console.log('FAIL', f, '#'+i, String(e.message||e).split('\n').slice(0,3).join(' | ')); }
  }
}
console.log('mermaid blocks:', total, 'failed:', bad);
