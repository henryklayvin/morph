/**
 * Runs everything the project considers proof that it works, on every JDK available:
 *
 *   node scripts/verify.mjs                 interface + Java suites + browser test
 *   node scripts/verify.mjs --skip-e2e      without the browser
 *
 * JDKs: the one in JAVA_HOME (must be 21) and .tools/jdk-25, or whatever MORPH_JDKS lists (path separator between).
 */
import { spawnSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const web = path.join(root, 'web');
const windows = process.platform === 'win32';
const jdks = process.env.MORPH_JDKS
  ? process.env.MORPH_JDKS.split(path.delimiter)
  : [process.env.JAVA_HOME, path.join(root, '.tools', 'jdk-25')].filter((p) => p && existsSync(p));
if (jdks.length === 0) fail('Set JAVA_HOME to a JDK 21 or MORPH_JDKS to a list of JDKs.');

run('npm ci', 'npm', ['ci', '--no-audit', '--no-fund'], web);
run('interface: tests', 'npm', ['test'], web);
run('interface: build', 'npm', ['run', 'build:local'], web);

const summary = [];
for (const jdk of jdks) {
  const env = { ...process.env, JAVA_HOME: jdk };
  const version = /version "([^"]+)"/.exec(String(spawnSync(path.join(jdk, 'bin', 'java'), ['-version']).stderr))?.[1];
  run(`Java ${version}: build and tests`, windows ? String.raw`.\mvnw.cmd` : './mvnw', ['-q', '-B', 'clean', 'verify', '-Prequire-web'], root, env);
  summary.push(`Java ${version}: ${count()}`);
  if (!process.argv.includes('--skip-e2e')) {
    run(`Java ${version}: browser`, 'npm', ['run', '-s', 'e2e'], web, env);
    summary.push(`Java ${version}: browser test passed`);
  }
}
console.log(`\n${summary.join('\n')}`);

function run(name, command, args, cwd, env = process.env) {
  console.log(`\n== ${name}`);
  // npm and mvnw are .cmd files on Windows and need the shell; every argument here is a constant.
  const { status } = spawnSync(command, args, { cwd, env, stdio: 'inherit', shell: windows });
  if (status !== 0) fail(`${name} failed`);
}

function count() {
  const totals = { tests: 0, failures: 0, errors: 0, skipped: 0 };
  const modules = [root, path.join(root, 'providers')].flatMap((d) => readdirSync(d).map((n) => path.join(d, n)));
  for (const module of modules) {
    const reports = path.join(module, 'target', 'surefire-reports');
    if (!existsSync(reports)) continue;
    for (const file of readdirSync(reports).filter((n) => n.startsWith('TEST-') && n.endsWith('.xml'))) {
      const suite = /<testsuite [^>]*>/.exec(readFileSync(path.join(reports, file), 'utf8'))?.[0] ?? '';
      for (const key of Object.keys(totals)) totals[key] += Number(new RegExp(` ${key}="(\\d+)"`).exec(suite)?.[1] ?? 0);
    }
  }
  return `${totals.tests} tests, ${totals.failures} failures, ${totals.errors} errors, ${totals.skipped} skipped`;
}

function fail(message) {
  console.error(`\n${message}`);
  process.exit(1);
}
