// Editor e2e: drive the real Narcis page in headless Chromium and check
// the editing behaviours the HTTP gates cannot see — where the caret is
// drawn, where a click lands, how the arrow/Home/End/Page keys move,
// selection and replacement, the fast-typing race, auto-pair, smart
// enter, line joining, a paredit chord through the menu, find and
// select-all. The checks run in one order on one fixture file, because
// several of them edit it; the last one replaces the whole buffer.
//
//   node test/page/editor_e2e.cjs <url> <shots-dir>
//
// Prints one E2E line per check and a SUMMARY line; exits non-zero on
// any failure. Needs the `playwright` package and its Chromium
// (scripts/editor-e2e.sh says how it is found).
const { chromium } = require('playwright');
const path = require('path');

const url = process.argv[2] || 'http://127.0.0.1:8396/';
const shots = process.argv[3] || '.';
let pass = 0, fail = 0;
function ok(cond, label, detail) {
  if (cond) { pass++; console.log(`E2E ${label} OK`); }
  else { fail++; console.log(`E2E ${label} FAIL${detail ? ' — ' + detail : ''}`); }
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
  const editorLines = () => page.evaluate(() => Array.from(document.querySelectorAll('#win .code')).map(e => e.textContent));
  const waitFor = async (f, ms = 15000) => {
    const t0 = Date.now();
    for (;;) {
      try { if (await f()) return true; } catch (_) {}
      if (Date.now() - t0 > ms) return false;
      await page.waitForTimeout(60);
    }
  };
  const pos = async () => {
    const t = await text('#status-pos');
    const m = /Ln (\d+), Col (\d+)/.exec(t || '');
    return m ? { ln: +m[1], col: +m[2] } : null;
  };
  // The x of the boundary before column `col` (0-based) on line `ln`
  // (1-based), and the row's centre y — measured from the painted text
  // itself, walking the line's text nodes, so syntax colouring cannot
  // fool it.
  const boundary = (ln, col) => page.evaluate(([ln, c]) => {
    const rows = document.querySelectorAll('#win .line');
    const row = rows[ln - 1];
    const code = row.querySelector('.code');
    const walker = document.createTreeWalker(code, NodeFilter.SHOW_TEXT);
    let node, rem = c;
    while ((node = walker.nextNode())) {
      if (rem <= node.length) break;
      rem -= node.length;
    }
    const r = document.createRange();
    if (node) { r.setStart(node, Math.min(rem, node.length)); r.setEnd(node, Math.min(rem, node.length)); }
    else { r.selectNodeContents(code); r.collapse(false); }
    const rect = r.getClientRects()[0];
    const rr = row.getBoundingClientRect();
    return { x: rect ? rect.left : rr.left, y: rr.top + rr.height / 2 };
  }, [ln, col]);
  const clickBoundary = async (ln, col) => {
    const b = await boundary(ln, col);
    await page.mouse.click(b.x, b.y);
    return b;
  };
  const caretX = () => page.evaluate(() => document.getElementById('caret').getBoundingClientRect().left);
  const lineText = async n => (await editorLines())[n - 1];
  // Caret moves are queued behind the ops ahead of them, so a fixed
  // sleep cannot say a move has landed; wait for the position itself.
  const posIs = (ln, col) => async () => JSON.stringify(await pos()) === JSON.stringify({ ln, col });

  await page.goto(url);
  // the fixture file ends with a newline, so the buffer has a seventh,
  // empty line under the six content lines
  ok(await waitFor(async () => (await editorLines()).length === 7 &&
       (await lineText(1)) === '0123456789' && (await lineText(6)) === '(foo) (bar)'),
     'page-loads-the-fixture', JSON.stringify(await editorLines()));
  await page.waitForTimeout(300);

  // --- the caret is drawn where the text is
  const b5 = await clickBoundary(1, 5);
  await page.waitForTimeout(400);
  const drawn = await caretX();
  ok(Math.abs(drawn - b5.x) <= 1.5, 'caret-drawn-on-the-boundary', `caret ${drawn} boundary ${b5.x}`);
  ok(JSON.stringify(await pos()) === JSON.stringify({ ln: 1, col: 6 }), 'click-on-boundary-5-is-col-6', JSON.stringify(await pos()));

  // --- arrows move the caret, and wrap across lines
  for (let i = 0; i < 5; i++) await page.keyboard.press('ArrowRight');
  await page.waitForTimeout(400);
  ok(JSON.stringify(await pos()) === JSON.stringify({ ln: 1, col: 11 }), 'five-rights-reach-line-end', JSON.stringify(await pos()));
  await page.keyboard.press('ArrowRight');
  await page.waitForTimeout(400);
  ok(JSON.stringify(await pos()) === JSON.stringify({ ln: 2, col: 1 }), 'right-at-end-wraps-to-next-line', JSON.stringify(await pos()));
  await page.keyboard.press('ArrowLeft');
  await page.waitForTimeout(400);
  ok(JSON.stringify(await pos()) === JSON.stringify({ ln: 1, col: 11 }), 'left-at-start-wraps-to-prev-line-end', JSON.stringify(await pos()));

  // --- Home and End
  await page.keyboard.press('End');
  await page.waitForTimeout(400);
  ok(JSON.stringify(await pos()) === JSON.stringify({ ln: 1, col: 11 }), 'end-is-line-end', JSON.stringify(await pos()));
  await page.keyboard.press('Home');
  await page.waitForTimeout(400);
  ok(JSON.stringify(await pos()) === JSON.stringify({ ln: 1, col: 1 }), 'home-is-line-start', JSON.stringify(await pos()));

  // --- goal column survives a short line
  await clickBoundary(1, 9);
  ok(await waitFor(posIs(1, 10)), 'click-at-column-10', JSON.stringify(await pos()));
  await page.keyboard.press('ArrowDown');
  await page.waitForTimeout(400);
  ok(JSON.stringify(await pos()) === JSON.stringify({ ln: 2, col: 4 }), 'down-clamps-to-short-line', JSON.stringify(await pos()));
  await page.keyboard.press('ArrowDown');
  await page.waitForTimeout(400);
  ok(JSON.stringify(await pos()) === JSON.stringify({ ln: 3, col: 10 }), 'down-restores-goal-column', JSON.stringify(await pos()));

  // --- PageUp and PageDown span the viewport
  await clickBoundary(1, 0);
  await page.waitForTimeout(300);
  await page.keyboard.press('PageDown');
  await page.waitForTimeout(400);
  ok(JSON.stringify(await pos()) === JSON.stringify({ ln: 7, col: 1 }), 'page-down-reaches-the-last-line', JSON.stringify(await pos()));
  await page.keyboard.press('PageUp');
  await page.waitForTimeout(400);
  ok(JSON.stringify(await pos()) === JSON.stringify({ ln: 1, col: 1 }), 'page-up-returns-to-the-first-line', JSON.stringify(await pos()));

  // --- fast typing, then arrows: the text lands whole and the arrows survive
  await clickBoundary(3, 10);
  ok(await waitFor(posIs(3, 11)), 'click-at-line-3-end', JSON.stringify(await pos()));
  await page.keyboard.type('abcdef', { delay: 0 });
  await page.keyboard.press('ArrowLeft');
  await page.keyboard.press('ArrowLeft');
  await page.waitForTimeout(1200);
  ok((await lineText(3)) === 'abcdefghijabcdef', 'fast-typing-text-exact', await lineText(3));
  ok(JSON.stringify(await pos()) === JSON.stringify({ ln: 3, col: 15 }), 'arrows-after-fast-typing-survive', JSON.stringify(await pos()));

  // --- shift-selection and replacement
  await clickBoundary(3, 0);
  await page.waitForTimeout(300);
  await page.keyboard.press('Shift+End');
  await page.waitForTimeout(400);
  await page.keyboard.type('Z', { delay: 15 });
  ok(await waitFor(async () => (await lineText(3)) === 'Z'), 'shift-end-type-replaces-the-line', await lineText(3));

  // --- auto-pair: the closer is supplied, and typing it overtypes it
  await clickBoundary(5, 1);
  ok(await waitFor(posIs(5, 2)), 'click-before-the-pair-position', JSON.stringify(await pos()));
  await page.keyboard.type('(y', { delay: 15 });
  ok(await waitFor(async () => (await lineText(5)) === 'x(y)'), 'auto-pair-supplies-the-closer', await lineText(5));
  // the caret sits before the supplied closer now; typing the closer
  // overtypes it instead of inserting a second one
  ok(await waitFor(posIs(5, 4)), 'caret-before-the-supplied-closer', JSON.stringify(await pos()));
  await page.keyboard.type(')', { delay: 15 });
  ok(await waitFor(async () => (await lineText(5)) === 'x(y)'), 'typing-the-closer-overtype', await lineText(5));
  ok(await waitFor(posIs(5, 5)), 'caret-after-the-overtyped-closer', JSON.stringify(await pos()));

  // --- smart enter splits the form and indents the continuation
  await clickBoundary(4, 18);
  ok(await waitFor(posIs(4, 19)), 'click-inside-the-defn', JSON.stringify(await pos()));
  await page.keyboard.press('Enter');
  ok(await waitFor(async () => (await editorLines()).length === 8), 'smart-enter-adds-a-line', JSON.stringify(await editorLines()));
  ok((await lineText(4)) === '(defn greet [name]', 'smart-enter-keeps-the-head', await lineText(4));
  ok((await lineText(5)) === '   (str "Hi " name))', 'smart-enter-indents-the-continuation', JSON.stringify(await lineText(5)));
  await shot('01-smart-enter');

  // --- backspace at a line start joins the lines again (the indent the
  // smart enter added stays — backspace deletes the newline, nothing else)
  await clickBoundary(5, 0);
  ok(await waitFor(posIs(5, 1)), 'click-at-the-continuation-start', JSON.stringify(await pos()));
  await page.keyboard.press('Backspace');
  ok(await waitFor(async () => (await editorLines()).length === 7 &&
       (await lineText(4)) === '(defn greet [name]   (str "Hi " name))'), 'backspace-at-line-start-joins', JSON.stringify(await editorLines()));

  // --- a paredit op through the menu: slurp the next form into (foo)
  await clickBoundary(6, 3);
  ok(await waitFor(posIs(6, 4)), 'click-inside-foo', JSON.stringify(await pos()));
  await page.click('.menu-button[data-menu="Edit"]');
  ok(await visible('#menu-panel'), 'edit-menu-opens');
  await page.hover('#menu-panel .menu-item:has-text("Structure")');
  ok(await waitFor(() => visible('#menu-sub')), 'structure-submenu-opens');
  await page.click('#menu-sub .menu-item:has-text("Slurp Forward")');
  ok(await waitFor(async () => (await lineText(6)) === '(foo (bar))'), 'slurp-forward-through-the-menu', await lineText(6));
  await shot('02-slurp');

  // --- find: the bar opens, the match is selected (the caret rests at
  // the match's end), Esc closes it, and typing replaces the match
  await page.keyboard.press('Control+f');
  ok(await waitFor(() => visible('#findbar')), 'find-bar-opens');
  await page.keyboard.type('greet', { delay: 15 });
  await page.keyboard.press('Enter');
  ok(await waitFor(posIs(4, 12)), 'find-selects-the-match', JSON.stringify(await pos()));
  await page.keyboard.press('Escape');
  ok(await waitFor(async () => !(await visible('#findbar'))), 'find-closes-on-escape');
  await page.keyboard.type('X', { delay: 15 });
  ok(await waitFor(async () => (await lineText(4)).startsWith('(defn X')), 'typing-replaces-the-found-match', await lineText(4));

  // --- select all, then type: the whole buffer is replaced
  await page.keyboard.press('Control+a');
  await page.waitForTimeout(400);
  await page.keyboard.type('z', { delay: 15 });
  ok(await waitFor(async () => (await editorLines()).join('\n') === 'z'), 'select-all-type-replaces-the-buffer', JSON.stringify(await editorLines()));

  ok(errors.length === 0, 'no-page-errors', errors.slice(0, 3).join(' | '));
  console.log(`E2E SUMMARY pass=${pass} fail=${fail} shots=${shots}`);
  await browser.close();
  process.exit(fail === 0 ? 0 : 1);
})().catch(e => { console.log('E2E crashed: ' + e.stack); process.exit(2); });
