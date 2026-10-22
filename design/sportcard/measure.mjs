import { chromium } from 'file:///G:/flutter/work/nba/design/_shared/node_modules/playwright/index.mjs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const htmlPath = path.resolve(process.argv[2]);
const browser = await chromium.launch({
  executablePath: 'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  args: ['--allow-file-access-from-files', '--disable-web-security', '--hide-scrollbars'],
});
const page = await browser.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 });
await page.goto(pathToFileURL(htmlPath).href, { waitUntil: 'load', timeout: 60000 });
await page.waitForTimeout(1500);

const ids = process.argv.slice(3);
for (const id of ids) {
  const r = await page.evaluate((sid) => {
    window.__SHOW_SCREEN(sid);
    const el = document.querySelector(`[data-screen="${sid}"]`);
    if (!el) return null;
    const sc = el.querySelector('.scroll, .pl-body, .dt-body');
    if (!sc) return { sid, note: 'no scroll container' };
    const parts = {};
    sc.querySelectorAll(':scope > *').forEach((c,i)=>{ parts[i+'_'+(c.className||c.tagName).toString().split(' ')[0]] = Math.round(c.getBoundingClientRect().height); });
    return { sid, clientH: sc.clientHeight, scrollH: sc.scrollHeight, overflow: sc.scrollHeight - sc.clientHeight, parts };
  }, id);
  console.log(JSON.stringify(r));
}
await browser.close();
