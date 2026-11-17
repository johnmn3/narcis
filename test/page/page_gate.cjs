// Page gate: drive the real Narcis page in headless Chromium — real mouse
// and key events against a running launcher — and check what the HTTP
// gates cannot see: the window's chrome, the menus, typing, Run and Stop,
// the Interactions console, completion, the file tree, tabs, themes.
//
//   node test/page/page_gate.cjs <url> <shots-dir>
//
// Prints one PAGE-GATE line per check and a SUMMARY line; exits non-zero
// on any failure. Screenshots of each stage go to <shots-dir>.
// Needs the `playwright` package and its Chromium (scripts/page-gate.sh
// says how it is found).
const { chromium } = require('playwright');
const path = require('path');

const url = process.argv[2] || 'http://127.0.0.1:8377/';
const shots = process.argv[3] || '.';
let pass = 0, fail = 0;
function ok(cond, label, detail) {
  if (cond) { pass++; console.log(`PAGE-GATE ${label} OK`); }
  else { fail++; console.log(`PAGE-GATE ${label} FAIL${detail ? ' — ' + detail : ''}`); }
}

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1100, height: 760 }, deviceScaleFactor: 2 });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  const shot = name => page.screenshot({ path: path.join(shots, name + '.png') });
  const text = sel => page.evaluate(s => { const e = document.querySelector(s); return e ? e.textContent : null; }, sel);
  const visible = sel => page.evaluate(s => {
    const e = document.querySelector(s);
    if (!e || getComputedStyle(e).display === 'none') return false;
    const r = e.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }, sel);
  const editorText = () => page.evaluate(() => Array.from(document.querySelectorAll('#win .code')).map(e => e.textContent).join('\n'));
  const waitFor = async (f, ms = 15000) => {
    const t0 = Date.now();
    for (;;) {
      try { if (await f()) return true; } catch (_) {}
      if (Date.now() - t0 > ms) return false;
      await page.waitForTimeout(60);
    }
  };
  const consoleText = () => text('#console-out');

  await page.goto(url);
  ok(await waitFor(async () => (await editorText()).includes('println')), 'page-loads-the-buffer');
  await page.waitForTimeout(300);

  // --- the window: jonquil's header bar, panes and status bar
  ok((await text('#title')) === 'demo.clj', 'title-is-the-file-name', await text('#title'));
  const buttons = await page.evaluate(() => Array.from(document.querySelectorAll('#hdr .btn')).map(b => b.textContent));
  ok(JSON.stringify(buttons) === JSON.stringify(['≡ Files', 'Open…', '+', '▶ Run', 'Stop', 'Save…']), 'header-bar-buttons', JSON.stringify(buttons));
  const menus = await page.evaluate(() => Array.from(document.querySelectorAll('.menu-button')).map(b => b.textContent));
  ok(JSON.stringify(menus) === JSON.stringify(['File', 'Edit', 'View', 'Search', 'Run', 'Help']), 'menu-bar', JSON.stringify(menus));
  ok((await text('#status-msg')) === 'Ready', 'status-ready', await text('#status-msg'));
  ok(/^Jolt\s+Ln 1, Col 1$/.test(await text('#status-pos')), 'status-position', await text('#status-pos'));
  ok((await text('#console-prompt')) === 'user=> ', 'console-prompt', await text('#console-prompt'));
  const layout = await page.evaluate(() => {
    const r = id => document.getElementById(id).getBoundingClientRect();
    return { hdr: r('hdr'), scroll: r('scroll'), con: r('console'), status: r('status'), h: window.innerHeight };
  });
  ok(layout.hdr.bottom <= layout.scroll.top + 1 && layout.scroll.bottom <= layout.con.top + 1 &&
     layout.con.bottom <= layout.status.top + 1 && Math.abs(layout.status.bottom - layout.h) < 2,
     'layout-header-editor-console-status');
  await shot('01-window');

  // --- menus: a panel with its own background, jonquil's items
  await page.click('.menu-button[data-menu="File"]');
  ok(await visible('#menu-panel'), 'file-menu-opens');
  const panel = await page.evaluate(() => {
    const p = document.getElementById('menu-panel');
    const cs = getComputedStyle(p);
    const r = p.getBoundingClientRect();
    const b = document.querySelector('.menu-button[data-menu="File"]').getBoundingClientRect();
    return { bg: cs.backgroundColor, items: Array.from(p.querySelectorAll('.menu-label')).map(e => e.textContent),
             under: Math.abs(r.top - b.bottom) < 2 && Math.abs(r.left - b.left) < 2 };
  });
  ok(/^rgb\(/.test(panel.bg), 'menu-panel-is-opaque', panel.bg);
  ok(panel.under, 'menu-panel-under-its-button');
  ok(JSON.stringify(panel.items) === JSON.stringify(['New Tab', 'Open…', 'Save', 'Save As…', 'Close Tab']), 'file-menu-items', JSON.stringify(panel.items));
  await shot('02-file-menu');
  await page.hover('.menu-button[data-menu="Edit"]');
  await page.waitForTimeout(100);
  await page.hover('#menu-panel .menu-item:has-text("Structure")');
  ok(await waitFor(() => visible('#menu-sub')), 'structure-submenu-opens');
  ok(((await text('#menu-sub')) || '').includes('Slurp Forward'), 'structure-submenu-items');
  await shot('03-structure-submenu');
  await page.keyboard.press('Escape');
  await page.mouse.click(600, 300);
  ok(!(await visible('#menu-panel')), 'menu-closes');

  // --- typing in the Definitions editor
  await page.mouse.click(400, 200);
  await page.keyboard.press('Control+End').catch(() => {});
  // go to the end of the buffer: click the last line's end
  const lastLine = await page.evaluate(() => {
    const rows = document.querySelectorAll('#win .line');
    const r = rows[rows.length - 1].getBoundingClientRect();
    return { x: r.right - 5, y: r.top + r.height / 2 };
  });
  await page.mouse.click(lastLine.x, lastLine.y);
  await page.keyboard.type('(defn twice [x] (* 2 x', { delay: 15 });
  ok(await waitFor(async () => (await editorText()).includes('(defn twice [x] (* 2 x))')), 'typing-with-auto-pair', await editorText());
  ok((await page.evaluate(() => document.activeElement && document.activeElement.id)) === 'input', 'focus-stays-in-the-editor');
  await page.keyboard.press('End');
  await page.keyboard.press('Enter');
  await page.keyboard.type('(twice 21', { delay: 15 });
  ok(await waitFor(async () => (await editorText()).includes('(twice 21)')), 'second-form-typed');
  await page.keyboard.press('Control+z');
  ok(await waitFor(async () => !(await editorText()).includes('(twice 21)')), 'undo-steps-back');
  await page.keyboard.press('Control+y');
  ok(await waitFor(async () => (await editorText()).includes('(twice 21)')), 'redo-steps-forward');
  await shot('04-typed');

  // --- Run: the header line, the output, the last value, the prompt
  await page.click('#btn-run');
  ok(await waitFor(async () => ((await consoleText()) || '').includes('=> 42')), 'run-shows-the-last-value', await consoleText());
  const ct = (await consoleText()) || '';
  ok(ct.includes('; --- Running demo.clj ---'), 'run-header-line');
  ok(ct.includes('Hello, Narcis!'), 'run-output');
  ok(await waitFor(async () => (await text('#status-msg')) === 'Ready'), 'status-back-to-ready');
  await shot('05-run');

  // --- the prompt evaluates in the program's world
  await page.click('#console');
  await page.keyboard.type('(twice 5)', { delay: 10 });
  await page.keyboard.press('Enter');
  ok(await waitFor(async () => ((await consoleText()) || '').includes('user=> (twice 5)\n=> 10')), 'prompt-evaluates', await consoleText());
  await page.keyboard.type('(map inc', { delay: 10 });
  await page.keyboard.press('Enter');
  ok(await waitFor(async () => (await page.inputValue('#console-in')).includes('\n')), 'open-bracket-continues-the-input');
  await page.keyboard.type(' [1 2 3])', { delay: 10 });
  await page.keyboard.press('Enter');
  ok(await waitFor(async () => ((await consoleText()) || '').includes('=> (2 3 4)')), 'multi-line-input-evaluates', await consoleText());
  await page.keyboard.press('ArrowUp');
  ok(await waitFor(async () => (await page.inputValue('#console-in')).includes('map inc')), 'history-recalls');
  await page.fill('#console-in', '');

  // --- Stop really stops
  await page.click('#console');
  await page.keyboard.type('(loop [] (recur))', { delay: 10 });
  await page.keyboard.press('Enter');
  ok(await waitFor(async () => (await text('#status-msg')) === 'Running…'), 'a-loop-is-running');
  await shot('06-running');
  await page.click('#btn-stop');
  ok(await waitFor(async () => ((await consoleText()) || '').includes('; interrupted')), 'stop-interrupts-the-loop', await consoleText());
  ok(await waitFor(async () => (await text('#status-msg')) === 'Ready'), 'ready-after-stop');
  await page.keyboard.type('(+ 40 2)', { delay: 10 });
  await page.keyboard.press('Enter');
  ok(await waitFor(async () => ((await consoleText()) || '').endsWith('user=> (+ 40 2)\n=> 42\n')), 'evaluates-again-after-stop', await consoleText());
  await shot('07-stopped');

  // --- completion with its doc preview
  await page.mouse.click(lastLine.x, lastLine.y);
  await page.keyboard.press('End');
  await page.keyboard.press('Enter');
  await page.keyboard.type('(ma', { delay: 15 });
  await page.keyboard.press('Control+Space');
  ok(await waitFor(() => visible('#completion')), 'completion-opens');
  ok(await waitFor(async () => ((await text('#completion-list')) || '').includes('macroexpand')), 'completion-candidates', await text('#completion-list'));
  ok(await waitFor(async () => ((await text('#completion-doc')) || '').length > 20), 'completion-doc-preview', await text('#completion-doc'));
  await shot('08-completion');
  await page.keyboard.press('Enter');
  ok(await waitFor(async () => /\(ma[a-z-]+\)/.test(await editorText()) && !(await visible('#completion'))), 'completion-accepts', await editorText());

  // --- file tree and tabs
  await page.click('#btn-files');
  ok(await waitFor(async () => ((await text('#tree')) || '').includes('demo.clj')), 'file-tree-lists-the-folder', await text('#tree'));
  await page.click('#btn-new');
  ok(await waitFor(() => visible('#tabs')), 'tab-strip-appears-with-two-tabs');
  ok(await waitFor(async () => (await text('#title')) === 'Untitled'), 'new-tab-is-untitled', await text('#title'));
  await shot('09-tree-and-tabs');
  await page.click('#tabs .tab:first-child');
  ok(await waitFor(async () => (await text('#title')) === 'demo.clj'), 'tab-click-switches');

  // --- themes: the stored choice repaints the whole window
  await page.click('.menu-button[data-menu="View"]');
  await page.hover('#menu-panel .menu-item:has(.menu-label:text-is("Theme"))');
  await waitFor(() => visible('#menu-sub'));
  await page.click('#menu-sub .menu-item:has-text("Dracula")');
  ok(await waitFor(async () => {
    const bg = await page.evaluate(() => getComputedStyle(document.getElementById('scroll')).backgroundColor);
    const m = /rgb\((\d+), (\d+), (\d+)\)/.exec(bg);
    return m && (+m[1] + +m[2] + +m[3]) < 300;
  }), 'dark-theme-repaints-the-editor');
  await page.waitForTimeout(200);
  await shot('10-dark-theme');
  await page.click('.menu-button[data-menu="View"]');
  await page.hover('#menu-panel .menu-item:has(.menu-label:text-is("Theme"))');
  await waitFor(() => visible('#menu-sub'));
  await page.click('#menu-sub .menu-item:has(.menu-label:text-is("Light"))');
  await page.waitForTimeout(300);

  // --- command palette
  await page.keyboard.press('Control+Shift+P');
  ok(await waitFor(() => visible('#palette')), 'palette-opens');
  await page.keyboard.type('slurp');
  ok(await waitFor(async () => ((await text('#palette-list')) || '').includes('Slurp Forward')), 'palette-filters');
  await shot('11-palette');
  await page.keyboard.press('Escape');

  ok(errors.length === 0, 'no-page-errors', errors.slice(0, 3).join(' | '));
  console.log(`PAGE-GATE SUMMARY pass=${pass} fail=${fail} shots=${shots}`);
  await browser.close();
  process.exit(fail === 0 ? 0 : 1);
})().catch(e => { console.log('PAGE-GATE crashed: ' + e.stack); process.exit(2); });
