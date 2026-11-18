// Narcis debugger e2e: the gutter breakpoint, the pause with its
// locals, evaluating in the paused frame, Continue, and Break on
// error — driven through the real page in headless Chromium, in the
// page gate's shape. The debugger needs a Jolt with jolt.debug (the
// nightly; the released Jolt has none): on one without it the suite
// prints SKIP and exits 0, as upstream's debugger check does.
//
// Run by scripts/debugger-e2e.sh, which starts the server.
const { chromium } = require('playwright');

const url = process.argv[2];
if (!url) { console.error('usage: debugger_e2e.cjs <url>'); process.exit(2); }
const shots = process.env.E2E_SHOTS || '/tmp/narcis-debugger-e2e-shots';
require('fs').mkdirSync(shots, { recursive: true });

let pass = 0, fail = 0;
const ok = (cond, name, detail) => {
  if (cond) { pass++; console.log('DBG', name, 'OK'); }
  else { fail++; console.log('DBG', name, 'FAIL', detail || ''); }
};
const waitFor = async (pred, ms = 20000) => {
  const t0 = Date.now();
  for (;;) {
    let v = false;
    try { v = await pred(); } catch (e) { v = false; }
    if (v) return true;
    if (Date.now() - t0 > ms) return false;
    await new Promise(r => setTimeout(r, 150));
  }
};

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1100, height: 800 } });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));

  const consoleText = () => page.evaluate(() => document.getElementById('console-out').textContent);
  const framesText = () => page.evaluate(() => document.getElementById('frames').textContent);
  const framesVisible = () => page.evaluate(() => {
    const f = document.getElementById('frames');
    return f && f.style.display !== 'none' && f.textContent.length > 0;
  });

  await page.goto(url);
  ok(await waitFor(async () => (await page.evaluate(() =>
    document.querySelectorAll('#win .code').length)) >= 4), 'page-loads');

  // a Jolt without jolt.debug: nothing here can run
  const available = await page.evaluate(async () => {
    const r = await fetch('/api/console?since=0');
    const edn = await r.text();
    return edn.includes(':available true');
  });
  if (!available) {
    console.log('DBG SUMMARY skip: this Jolt has no jolt.debug');
    await browser.close();
    process.exit(0);
  }

  // --- mark a breakpoint on line 3 by clicking its gutter
  const gut = await page.evaluate(() => {
    const row = document.querySelectorAll('#win .line')[2];
    const g = row.querySelector('.gutter');
    const r = g.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  });
  await page.mouse.click(gut.x, gut.y);
  ok(await waitFor(async () => await page.evaluate(() => {
    const row = document.querySelectorAll('#win .line')[2];
    return row.querySelector('.gutter').textContent.includes('●');
  })), 'gutter-click-marks-the-breakpoint');

  // --- Run: the program pauses at the breakpoint with its locals
  await page.click('#btn-run');
  ok(await waitFor(async () => (await framesVisible()) &&
       (await framesText()).includes('Breakpoint at line 3')),
     'run-pauses-at-the-breakpoint', await framesText());
  ok((await framesText()).includes('y = 42'), 'the-paused-frame-shows-its-locals', await framesText());
  await page.screenshot({ path: shots + '/01-paused.png' });

  // --- evaluate in the paused frame
  await page.fill('#frames-eval-in', '(+ y 1)');
  await page.press('#frames-eval-in', 'Enter');
  ok(await waitFor(async () => (await framesText()).includes('43')),
     'eval-in-the-paused-frame', await framesText());

  // --- Continue: the program finishes and prints
  await page.evaluate(() => {
    const bs = [...document.querySelectorAll('#frames button')];
    bs.find(b => b.textContent === 'Continue').click();
  });
  ok(await waitFor(async () => (await consoleText()).includes('84')),
     'continue-finishes-the-program', await consoleText());
  ok(await waitFor(async () => !(await framesText()).includes('Continue')),
     'the-pause-controls-go-away');

  // --- Break on error: a throw pauses, Continue lets it land. The
  // program goes through the console's own prompt (a plain textarea:
  // no editor auto-pair between the suite and the evaluator); Run's
  // break-on-error is covered by narcis.debugger-live-test.
  await page.evaluate(() => {
    const bs = [...document.querySelectorAll('#frames button')];
    bs.find(b => b.textContent.startsWith('Break on error')).click();
  });
  ok(await waitFor(async () => (await framesText()).includes('Break on error: on')),
     'break-on-error-turns-on', await framesText());
  await page.click('#console-in');
  await page.keyboard.type('(defn g [x] (/ x 0))', { delay: 5 });
  await page.keyboard.press('Enter');
  ok(await waitFor(async () => (await consoleText()).includes("#'user/g")),
     'the-console-defines-g', await consoleText());
  await page.keyboard.type('(g 1)', { delay: 5 });
  await page.keyboard.press('Enter');
  ok(await waitFor(async () => (await framesText()).includes('Divide by zero') &&
       (await framesText()).includes('Continue')),
     'break-on-error-pauses-at-the-throw',
     (await framesText()) + ' || console: ' + (await consoleText()));
  await page.evaluate(() => {
    const bs = [...document.querySelectorAll('#frames button')];
    bs.find(b => b.textContent === 'Continue').click();
  });
  ok(await waitFor(async () => (await consoleText()).includes('Divide by zero')),
     'the-error-lands-after-continue', await consoleText());
  await page.screenshot({ path: shots + '/02-break-on-error.png' });

  ok(errors.length === 0, 'no-page-errors', errors.join(' | '));
  console.log(`DBG SUMMARY pass=${pass} fail=${fail} shots=${shots}`);
  await browser.close();
  process.exit(fail ? 1 : 0);
})().catch(e => { console.log('DBG crashed:', e && e.stack || e); process.exit(1); });
