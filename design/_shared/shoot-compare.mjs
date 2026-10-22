#!/usr/bin/env node
/**
 * 渲染 design/compare.html 并整页截图，输出 design/compare.png。
 * 路径相对本脚本解析，不写死机器绝对路径。
 */
import { chromium } from 'playwright';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolveBrowserExecutable } from './chrome.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DESIGN_DIR = path.resolve(__dirname, '..');

let browser;
try {
  browser = await chromium.launch({
    executablePath: resolveBrowserExecutable(chromium),
    args: ['--allow-file-access-from-files'],
  });
  const page = await browser.newPage({ viewport: { width: 1680, height: 1200 }, deviceScaleFactor: 1.5 });

  const html = path.join(DESIGN_DIR, 'compare.html');
  const out = path.join(DESIGN_DIR, 'compare.png');
  await page.goto(pathToFileURL(html).href, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);

  await page.screenshot({ path: out, fullPage: true });

  // 检查图片是否都加载成功
  const broken = await page.evaluate(() =>
    Array.from(document.images)
      .filter((i) => !i.complete || i.naturalWidth === 0)
      .map((i) => i.getAttribute('src')),
  );

  console.log(JSON.stringify({ out, broken }, null, 2));
} finally {
  if (browser) await browser.close();
}
