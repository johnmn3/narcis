// Big-file e2e: drive the real Narcis page in headless Chromium over a
// lorem-ipsum file of hundreds of megabytes and measure the
// interactions: reaching the end of the file by finding its unique
// end token, a typing burst there, undo/redo, a mid-file edit, and
// page-down scrolling. Phase 2 runs against a relaunched server on
// the same store and checks the edit made in phase 1 is still there
// (bring-up after closing, after edits) and still undoable.
//
//   node test/page/bigfile_e2e.cjs <url> <shots-dir> <phase> \
//        <expected-lines> <mid-marker-line>
//
// Prints one E2E line per check, PERF lines with milliseconds, and a
// SUMMARY line; exits non-zero on any failure.
const { chromium } = require('playwright');
const path = require('path');

const url = process.argv[2] || 'http://127.0.0.1:8398/';
const shots = process.argv[3] || '.';
const phase = process.argv[4] || 'edit';
const expectedLines = parseInt(process.argv[5] || '0', 10);
const midLine = parseInt(process.argv[6] || '0', 10);
let pass = 0, fail = 0;
function ok(cond, label, detail) {
  if (cond) { pass++; console.log(`E2E ${label} OK`); }
  else { fail++; console.log(`E2E ${label} FAIL${detail ? ' — ' + detail : ''}`); }
}
function perf(label, ms) { console.log(`PERF ${label} ${Math.round(ms)}ms`); }

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1100, height: 760 }, deviceScaleFactor: 2 });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  const text = sel => page.evaluate(s => { const e = document.querySelector(s); return e ? e.textContent : null; }, sel);
  const visible = sel => page.evaluate(s => {
    const e = document.querySelector(s);
    if (!e || getComputedStyle(e).display === 'none') return false;
    const r = e.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }, sel);
  const editorLines = () => page.evaluate(() => Array.from(document.querySelectorAll('#win .code')).map(e => e.textContent));
  const waitFor = async (f, ms = 120000) => {
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
  const posLn = async ln => { const p = await pos(); return p && p.ln === ln; };
  // find a unique token that sits on line `ln`; lands the caret at
  // the match's end with the match selected. Waits until the status
  // bar actually shows that line — on a file this size the scan
  // takes tens of seconds, and a position that never renders is a
  // failure, not a slow pass. Returns the find's milliseconds.
  const findToken = async (tok, ln) => {
    const t0 = Date.now();
    await page.keyboard.press('Control+f');
    await waitFor(() => visible('#findbar'), 10000);
    await page.keyboard.type(tok, { delay: 0 });
    await page.keyboard.press('Enter');
    const landed = await waitFor(() => posLn(ln), 300000);
    await page.keyboard.press('Escape');
    return landed ? Date.now() - t0 : -1;
  };
  const lastVisibleLine = async () => {
    const ls = await editorLines();
    return ls[ls.length - 1] || '';
  };
  // the buffer's last line is empty (the file ends with a newline),
  // so end-of-file content sits on the second-to-last rendered line:
  // assert over every rendered line, not just the last one
  const anyLine = async tok => (await editorLines()).some(l => l.includes(tok));
  const lineWith = async tok => (await editorLines()).find(l => l.includes(tok)) || '';

  const tLoad = Date.now();
  await page.goto(url);
  ok(await waitFor(async () => (await editorLines()).length > 0 &&
       (await editorLines())[0].includes('zqxfirst'), 1200000),
     'page-renders-the-big-file', JSON.stringify((await editorLines())[0] || '').slice(0, 60));
  perf('page-first-render-after-server-up', Date.now() - tLoad);

  if (phase === 'edit') {
    // --- find to the end of the file: the jump and the search in one
    const ms = await findToken('zqxfinal', expectedLines);
    ok(await posLn(expectedLines), 'find-reaches-the-last-line',
       JSON.stringify(await pos()) + ' expected ln ' + expectedLines);
    if (ms >= 0) perf('find-to-end-of-file', ms);

    // --- typing burst at the end of the file (one batched op)
    await page.keyboard.press('End');
    const t0 = Date.now();
    await page.keyboard.type(' zqxedit', { delay: 0 });
    ok(await waitFor(async () => anyLine('zqxedit')),
       'typing-burst-lands-at-eof', await lineWith('zqxfinal'));
    perf('type-8-chars-at-eof-op-and-render', Date.now() - t0);

    // --- undo removes the burst, redo restores it (the edit that
    // must survive the restart in phase 2)
    await page.keyboard.press('Control+z');
    ok(await waitFor(async () => !(await anyLine('zqxedit'))),
       'undo-removes-the-burst', await lineWith('zqxfinal'));
    await page.keyboard.press('Control+y');
    ok(await waitFor(async () => anyLine('zqxedit')),
       'redo-restores-the-burst', await lineWith('zqxfinal'));

    // --- a mid-file edit: find the mid marker, type, undo it
    const msMid = await findToken('zqx' + midLine, midLine);
    ok(await posLn(midLine), 'find-reaches-the-mid-marker',
       JSON.stringify(await pos()) + ' expected ln ' + midLine);
    if (msMid >= 0) perf('find-to-mid-file', msMid);
    await page.keyboard.press('End');
    const t1 = Date.now();
    await page.keyboard.type(' zqxmid', { delay: 0 });
    ok(await waitFor(async () => (await editorLines()).some(l => l.includes('zqxmid'))),
       'mid-file-edit-lands');
    perf('type-7-chars-mid-file-op-and-render', Date.now() - t1);
    await page.keyboard.press('Control+z');
    ok(await waitFor(async () => !(await editorLines()).some(l => l.includes('zqxmid'))),
       'mid-file-edit-undoes');

    // --- scrolling: back to line 1 by its token, then 10 page-downs
    await findToken('zqxfirst', 1);
    ok(await posLn(1), 'find-reaches-line-1', JSON.stringify(await pos()));
    const before = await pos();
    const t2 = Date.now();
    for (let i = 0; i < 10; i++) await page.keyboard.press('PageDown');
    ok(await waitFor(async () => { const p = await pos(); return p && p.ln > before.ln + 100; }),
       'page-downs-move-through-the-file', JSON.stringify(await pos()));
    const after = await pos();
    perf(`page-down-x10 (${after.ln - before.ln} lines)`, Date.now() - t2);

    // --- leave the caret deep in the file: phase 2 reopens on this
    // store, and its restored caret must render from there
    const msLeave = await findToken('zqx' + midLine, midLine);
    ok(await posLn(midLine), 'phase-1-leaves-the-caret-mid-file',
       JSON.stringify(await pos()));
    if (msLeave >= 0) perf('find-to-mid-file-again', msLeave);
  } else {
    // --- phase 2: the server was relaunched on the same store. The
    // page must open at the restored caret (phase 1 left it a few
    // hundred lines in, past the first window) with a position
    // readout — the regression this suite caught: a caret outside
    // the held window rendered no position and no caret at all.
    ok(await waitFor(() => posLn(midLine), 60000),
       'restored-caret-renders-its-position', JSON.stringify(await pos()));
    // The edit typed at the end of the file in phase 1 must be
    // present, and the undo history with it.
    const ms = await findToken('zqxfinal', expectedLines);
    ok(await posLn(expectedLines), 'after-restart-find-reaches-the-last-line',
       JSON.stringify(await pos()) + ' expected ln ' + expectedLines);
    if (ms >= 0) perf('after-restart-find-to-end', ms);
    ok(await anyLine('zqxedit'),
       'the-edit-survived-the-restart', await lineWith('zqxfinal'));
    await page.keyboard.press('Control+z');
    // undo steps back over the phase-1 edit session's last edit on
    // this buffer: the EOF burst (the mid-file edit was already
    // undone in phase 1, and the scrolls/finds are not edits)
    ok(await waitFor(async () => !(await anyLine('zqxedit'))),
       'undo-after-restart-removes-the-edit', await lineWith('zqxfinal'));
  }

  ok(errors.length === 0, 'no-page-errors', errors.slice(0, 3).join(' | '));
  console.log(`E2E SUMMARY pass=${pass} fail=${fail} shots=${shots}`);
  await browser.close();
  process.exit(fail === 0 ? 0 : 1);
})().catch(e => { console.log('E2E crashed: ' + e.stack); process.exit(2); });
