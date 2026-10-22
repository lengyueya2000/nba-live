#!/usr/bin/env node
/**
 * 量一量 mockup 页面各屏的滚动高度与直接子块高度。
 *
 * 用法:
 *   node measure.mjs <html-path> [screen-id ...]
 * 例:
 *   node measure.mjs ../sportcard/index.html 01-list-dark 02-player-dark
 *
 * 所有路径都相对本脚本解析，脚本放在任何目录都能跑。
 */
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SHARED_DIR = path.resolve(__dirname, '..', '_shared');

/* Playwright 装在 design/_shared/node_modules 下：用 file:// URL 动态载入，
   不写死机器绝对路径，也不需要在本目录再装一份依赖。 */
const playwrightEntry = pathToFileURL(
  path.join(SHARED_DIR, 'node_modules', 'playwright', 'index.mjs'),
).href;
const { chromium } = await import(playwrightEntry);
const { resolveBrowserExecutable } = await import(
  pathToFileURL(path.join(SHARED_DIR, 'chrome.mjs')).href
);

const htmlArg = process.argv[2];
if (!htmlArg) {
  console.error('用法: node measure.mjs <html-path> [screen-id ...]');
  console.error('例:   node measure.mjs ../sportcard/index.html 01-list-dark');
  process.exit(1);
}

const htmlPath = path.resolve(htmlArg);
const ids = process.argv.slice(3);

let browser;
try {
  browser = await chromium.launch({
    executablePath: resolveBrowserExecutable(chromium),
    args: ['--allow-file-access-from-files', '--disable-web-security', '--hide-scrollbars'],
  });
  const page = await browser.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 });
  await page.goto(pathToFileURL(htmlPath).href, { waitUntil: 'load', timeout: 60000 });
  await page.waitForTimeout(1500);

  for (const id of ids) {
    const r = await page.evaluate((sid) => {
      if (typeof window.__SHOW_SCREEN !== 'function') {
        throw new Error(
          '页面没有暴露 window.__SHOW_SCREEN(sid)，无法切换屏幕。' +
            '请确认这是一个支持多屏切换的 mockup（见 design/README.md）。',
        );
      }
      window.__SHOW_SCREEN(sid);
      const el = document.querySelector(`[data-screen="${sid}"]`);
      if (!el) return null;
      const sc = el.querySelector('.scroll, .pl-body, .dt-body');
      if (!sc) return { sid, note: 'no scroll container' };
      const parts = {};
      sc.querySelectorAll(':scope > *').forEach((c, i) => {
        parts[i + '_' + (c.className || c.tagName).toString().split(' ')[0]] = Math.round(
          c.getBoundingClientRect().height,
        );
      });
      return {
        sid,
        clientH: sc.clientHeight,
        scrollH: sc.scrollHeight,
        overflow: sc.scrollHeight - sc.clientHeight,
        parts,
      };
    }, id);
    console.log(JSON.stringify(r));
  }
} catch (err) {
  console.error('measure 失败: ' + (err && err.message ? err.message : String(err)));
  process.exitCode = 1;
} finally {
  if (browser) await browser.close();
}
