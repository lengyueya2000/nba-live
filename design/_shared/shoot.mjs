#!/usr/bin/env node
/**
 * 共享截图工具：把 mockup HTML 渲染为手机视口 PNG。
 *
 * 用法:
 *   node shoot.mjs <html-path> <out-dir> [--light]
 *
 * 约定:
 *   - 视口 390x844, deviceScaleFactor 2
 *   - HTML 通过 window.__SCREENS 提供多屏定义:
 *       [{ id, name, selector }]
 *     若未提供 __SCREENS，则回退为按 data-screen 属性的元素逐个截。
 *   - --light 模式追加截一套浅色主题。
 */
import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { resolveBrowserExecutable } from './chrome.mjs';

const args = process.argv.slice(2);
const htmlPath = path.resolve(args[0] ?? '.');
const outDir = path.resolve(args[1] ?? './out');
const wantLight = args.includes('--light');

await mkdir(outDir, { recursive: true });

const browser = await chromium.launch({
  executablePath: resolveBrowserExecutable(chromium),
  args: ['--allow-file-access-from-files', '--disable-web-security', '--hide-scrollbars'],
});
const page = await browser.newPage({
  viewport: { width: 390, height: 844 },
  deviceScaleFactor: 2,
});

const errors = [];
const remoteAssetFailures = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => {
  if (m.type() !== 'error') return;
  const text = m.text();
  // 队徽走明文 HTTP 的远程 CDN（jrszbt.com / sportnanoapi.com），离线或源站挂掉时
  // 浏览器会报 "Failed to load resource" —— 那不是原型代码的 bug，不能因此判定失败。
  const loc = (m.location() && m.location().url) || '';
  if (/Failed to load resource/i.test(text) && /^https?:/i.test(loc)) {
    remoteAssetFailures.push(text);
    return;
  }
  errors.push(text);
});

await page.goto(pathToFileURL(htmlPath).href, { waitUntil: 'networkidle' });
await page.waitForTimeout(600);

const screens = await page.evaluate(() => {
  if (window.__SCREENS) return window.__SCREENS;
  return Array.from(document.querySelectorAll('[data-screen]')).map((el) => ({
    id: el.getAttribute('data-screen'),
    name: el.getAttribute('data-screen'),
    selector: `[data-screen="${el.getAttribute('data-screen')}"]`,
  }));
});

if (!screens.length) {
  console.error('NO_SCREENS: page exposes neither window.__SCREENS nor [data-screen] elements');
  await browser.close();
  process.exit(2);
}

const results = [];

async function shoot(name, selector, file) {
  const target = await page.$(selector);
  if (!target) {
    results.push({ name, file, ok: false, err: `selector not found: ${selector}` });
    return;
  }
  await target.screenshot({ path: path.join(outDir, file) });
  results.push({ name, file, ok: true });
}

for (const sc of screens) {
  // 切换到该屏
  await page.evaluate((sid) => {
    if (window.__SHOW_SCREEN) window.__SHOW_SCREEN(sid);
    else {
      document.querySelectorAll('[data-screen]').forEach((el) => {
        el.style.display = el.getAttribute('data-screen') === sid ? '' : 'none';
      });
    }
  }, sc.id);
  await page.waitForTimeout(350);

  await shoot(sc.name, sc.selector, `${sc.id}.png`);
}

if (wantLight) {
  await page.evaluate(() => {
    document.documentElement.setAttribute('data-theme', 'light');
    if (window.__SET_THEME) window.__SET_THEME('light');
  });
  await page.waitForTimeout(350);
  for (const sc of screens) {
    await page.evaluate((sid) => {
      if (window.__SHOW_SCREEN) window.__SHOW_SCREEN(sid);
    }, sc.id);
    await page.waitForTimeout(250);
    await shoot(`${sc.name}-light`, sc.selector, `${sc.id}-light.png`);
  }
  await page.evaluate(() => {
    document.documentElement.setAttribute('data-theme', 'dark');
    if (window.__SET_THEME) window.__SET_THEME('dark');
  });
}

// 溢出检测：文字超出容器
const overflow = await page.evaluate(() => {
  const bad = [];
  document.querySelectorAll('*').forEach((el) => {
    if (el.scrollWidth > el.clientWidth + 2 && el.clientWidth > 0) {
      const cs = getComputedStyle(el);
      if (cs.overflow === 'visible' && cs.textOverflow !== 'ellipsis' && el.children.length === 0) {
        bad.push({ tag: el.tagName, cls: el.className, text: (el.textContent || '').slice(0, 40) });
      }
    }
  });
  return bad.slice(0, 25);
});

console.log(
  JSON.stringify(
    { results, errors: errors.slice(0, 10), remoteAssetFailures: remoteAssetFailures.slice(0, 10), overflow },
    null,
    2,
  ),
);
await browser.close();
process.exit(errors.length ? 1 : 0);
