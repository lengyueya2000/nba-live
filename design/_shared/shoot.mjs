#!/usr/bin/env node
/**
 * 共享截图工具：把 mockup HTML 渲染为手机视口 PNG。
 *
 * 用法:
 *   node shoot.mjs <html-path> <out-dir> [--light] [--states]
 *
 * 约定:
 *   - 视口 390x844, deviceScaleFactor 2
 *   - HTML 通过 window.__SCREENS 提供多屏定义:
 *       [{ id, name, selector, states?: [{id, name, apply?: string}] }]
 *     若未提供 __SCREENS，则回退为按 data-screen 属性的元素逐个截。
 *   - --states 模式下会对每个 state 应用对应 CSS 类后额外截图。
 */
import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const args = process.argv.slice(2);
const htmlPath = path.resolve(args[0] ?? '.');
const outDir = path.resolve(args[1] ?? './out');
const wantLight = args.includes('--light');
const wantStates = args.includes('--states');

await mkdir(outDir, { recursive: true });

const CHROME_CANDIDATES = [
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
];
const { existsSync } = await import('node:fs');
const exePath = CHROME_CANDIDATES.find((p) => existsSync(p));

const browser = await chromium.launch({
  ...(exePath ? { executablePath: exePath } : {}),
  args: ['--allow-file-access-from-files', '--disable-web-security', '--hide-scrollbars'],
});
const page = await browser.newPage({
  viewport: { width: 390, height: 844 },
  deviceScaleFactor: 2,
});

const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => {
  if (m.type() === 'error') errors.push(m.text());
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

  if (wantStates && sc.states) {
    for (const st of sc.states) {
      await page.evaluate(
        ({ sid, stid, apply }) => {
          if (window.__APPLY_STATE) window.__APPLY_STATE(sid, stid);
          else if (apply) {
            document.querySelectorAll('[data-screen]').forEach((el) => {
              el.classList.toggle('__state-' + stid, el.getAttribute('data-screen') === sid);
            });
          }
        },
        { sid: sc.id, stid: st.id, apply: st.apply },
      );
      await page.waitForTimeout(280);
      await shoot(`${sc.id}-${st.id}`, sc.selector, `${sc.id}-${st.id}.png`);
      await page.evaluate((stid) => {
        if (window.__RESET_STATE) window.__RESET_STATE(stid);
        else
          document.querySelectorAll('.__state-' + stid).forEach((el) => el.classList.remove('__state-' + stid));
      }, st.id);
    }
  }
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

console.log(JSON.stringify({ results, errors: errors.slice(0, 10), overflow }, null, 2));
await browser.close();
process.exit(errors.length ? 1 : 0);
