import { chromium } from 'playwright';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const CHROME = [
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
].find((p) => existsSync(p));

const browser = await chromium.launch({ ...(CHROME ? { executablePath: CHROME } : {}), args: ['--allow-file-access-from-files'] });
const page = await browser.newPage({ viewport: { width: 1680, height: 1200 }, deviceScaleFactor: 1.5 });

const html = path.resolve('G:/flutter/work/nba/design/compare.html');
await page.goto(pathToFileURL(html).href, { waitUntil: 'networkidle' });
await page.waitForTimeout(1200);

const out = path.resolve('G:/flutter/work/nba/design/compare.png');
await page.screenshot({ path: out, fullPage: true });

// 检查图片是否都加载成功
const broken = await page.evaluate(() =>
  Array.from(document.images)
    .filter((i) => !i.complete || i.naturalWidth === 0)
    .map((i) => i.getAttribute('src')),
);

console.log(JSON.stringify({ out, broken }, null, 2));
await browser.close();
