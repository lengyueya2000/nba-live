import { chromium } from 'playwright';
import { existsSync } from 'node:fs';

const CHROME = [
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
].find((p) => existsSync(p));

const TARGETS = [
  { name: 'txf', url: 'http://play.sportsteam356.com/play/txf/?id=10022600092&id2=' },
  { name: 'mgxl', url: 'http://play.sportsteam356.com/play/mgxl/?id=00588264&id2=' },
  { name: 'pao', url: 'http://play.sportsteam356.com/play/pao/?id=39315342&id2=' },
  { name: 'wen', url: 'http://play.sportsteam356.com/play/wen/?id=112fae77aa2b3f9e768036b61b86d589&id2=' },
  { name: 'sm', url: 'http://play.sportsteam356.com/play/439.html' },
];

const browser = await chromium.launch({
  ...(CHROME ? { executablePath: CHROME } : {}),
  args: ['--autoplay-policy=no-user-gesture-required'],
});

for (const t of TARGETS) {
  const page = await browser.newPage({
    viewport: { width: 390, height: 700 },
    userAgent:
      'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
  });
  const hits = [];
  page.on('request', (r) => {
    const u = r.url();
    if (u.includes('.m3u8') || u.includes('.ts?') || u.includes('msss')) hits.push(u.slice(0, 130));
  });
  try {
    await page.goto(t.url, { waitUntil: 'domcontentloaded', timeout: 25000 });
    await page.waitForTimeout(6000);
    const info = await page.evaluate(() => ({
      videos: document.querySelectorAll('video').length,
      iframes: [...document.querySelectorAll('iframe')].map((f) => f.src).slice(0, 3),
      text: (document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 120),
    }));
    console.log(
      JSON.stringify({ name: t.name, ...info, netHits: hits.slice(0, 3), hitCount: hits.length }, null, 1),
    );
  } catch (e) {
    console.log(JSON.stringify({ name: t.name, error: String(e).slice(0, 120) }));
  }
  await page.close();
}

await browser.close();
