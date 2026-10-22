#!/usr/bin/env node
/**
 * 浏览器可执行文件查找（相对脚本解析路径，不写死机器绝对路径）。
 *
 * 顺序：
 *   1. 环境变量 CHROME_PATH（显式覆盖）
 *   2. 常见的 Chrome / Edge 安装位置
 *   3. Playwright 自带的 Chromium
 * 都找不到时抛出带完整候选列表的错误，而不是让 playwright 抛一句含糊的 "Executable doesn't exist"。
 */
import { existsSync } from 'node:fs';

export const CHROME_CANDIDATES = [
  process.env.CHROME_PATH,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Users/Default/AppData/Local/Google/Chrome/Application/chrome.exe',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome',
  '/usr/bin/google-chrome-stable',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
].filter(Boolean);

/** 命中第一个存在的系统浏览器；没有则返回 null */
export function findChrome() {
  return CHROME_CANDIDATES.find((p) => existsSync(p)) || null;
}

/** Playwright 自带 Chromium 的可执行文件；没装浏览器内核则返回 null */
export function bundledChromiumPath(chromium) {
  try {
    const p = chromium.executablePath();
    return p && existsSync(p) ? p : null;
  } catch {
    return null;
  }
}

/**
 * 返回一个可用的浏览器可执行文件路径。
 * @param {import('playwright').chromium} chromium
 * @returns {string}
 */
export function resolveBrowserExecutable(chromium) {
  const system = findChrome();
  if (system) return system;

  const bundled = bundledChromiumPath(chromium);
  if (bundled) return bundled;

  throw new Error(
    '找不到可用的浏览器可执行文件（Chrome / Edge / Playwright Chromium）。\n' +
      '请安装 Google Chrome 或 Edge，或设置环境变量 CHROME_PATH 指向可执行文件。\n' +
      '已尝试以下路径：\n' +
      CHROME_CANDIDATES.map((p) => '  - ' + p).join('\n'),
  );
}
