// lead-acid init/sync/add/remove into a temporary project (no Gradle run here: the APK build is checked by hand and on
// the phone). What matters is that the two lists agree: the Gradle dependencies and the factory list come from one list.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, existsSync, writeFileSync, mkdirSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const CLI = join(dirname(fileURLToPath(import.meta.url)), '..', 'cli.mjs');
const run = (cwd, ...args) => spawnSync(process.execPath, [CLI, ...args], { cwd, encoding: 'utf8' });
const project = () => { const d = mkdtempSync(join(tmpdir(), 'lead-acid-')); mkdirSync(join(d, 'dist')); writeFileSync(join(d, 'dist', 'x.html'), '<p>x'); return d; };
const read = (d, p) => readFileSync(join(d, p), 'utf8');

test('init writes android/ once, with the plugins in both places', () => {
  const d = project();
  const r = run(d, 'init', '--id', 'org.example.x', '--label', 'X & co', '--artifact', 'dist/x.html', '--plugins', 'intent,intake', '--intake', 'text/csv');
  assert.equal(r.status, 0, r.stderr);
  const deps = read(d, 'android/app/leadacid.gradle.kts'), kt = read(d, 'android/app/src/main/java/org/example/x/LeadAcidPlugins.kt');
  assert.match(deps, /project\(":plugin-intent"\)/); assert.match(deps, /project\(":plugin-intake"\)/); assert.match(deps, /project\(":core"\)/);
  assert.match(deps, /rootProject\.file\("\.\.\/dist\/x\.html"\)/);
  assert.match(kt, /^package org\.example\.x$/m); assert.match(kt, /listOf\(IntentHandoff, Intake\)/);
  assert.match(read(d, 'android/leadacid.settings.gradle.kts'), /listOf\("core", "plugin-intent", "plugin-intake"\)/);
  const manifest = read(d, 'android/app/src/main/AndroidManifest.xml');
  assert.match(manifest, /android:label="X &amp; co"/); assert.match(manifest, /mimeType="text\/csv"/);
  const app = read(d, 'android/app/build.gradle.kts');
  assert.match(app, /applicationId = "org\.example\.x"/); assert.match(app, /apply\(from = "leadacid\.gradle\.kts"\)/); assert.doesNotMatch(app, /plugin-share/);
  assert.match(read(d, 'android/gradle.properties'), /^leadacid\.dir=\S+$/m);
  assert.ok(existsSync(join(d, 'android/gradlew')) && existsSync(join(d, 'android/gradle/wrapper/gradle-wrapper.jar')));
  assert.ok(!read(d, 'android/gradlew').includes('\r'), 'gradlew is LF, whatever the checkout did');
  assert.ok(!existsSync(join(d, 'android/app/src/main/assets')), 'no template artifact to collide with the copied one');
  assert.deepEqual(JSON.parse(read(d, 'lead-acid.json')).plugins, ['intent', 'intake']);
  assert.notEqual(run(d, 'init', '--id', 'org.example.x', '--label', 'X', '--artifact', 'dist/x.html').status, 0, 'a second init refuses');
});

test('add and remove rewrite only the generated files', () => {
  const d = project();
  assert.equal(run(d, 'init', '--id', 'org.example.y', '--label', 'Y', '--artifact', 'dist/x.html').status, 0);
  assert.match(read(d, 'android/app/src/main/java/org/example/y/LeadAcidPlugins.kt'), /emptyList\(\)/);
  const activity = read(d, 'android/app/src/main/java/org/example/y/InstrumentActivity.kt');
  assert.equal(run(d, 'add', 'share', 'tree').status, 0);
  assert.match(read(d, 'android/app/src/main/java/org/example/y/LeadAcidPlugins.kt'), /listOf\(Share, Tree\)/);
  assert.equal(run(d, 'remove', 'share').status, 0);
  assert.match(read(d, 'android/app/src/main/java/org/example/y/LeadAcidPlugins.kt'), /listOf\(Tree\)/);
  assert.doesNotMatch(read(d, 'android/app/leadacid.gradle.kts'), /plugin-share/);
  assert.equal(read(d, 'android/app/src/main/java/org/example/y/InstrumentActivity.kt'), activity, 'the activity is the app\'s own');
  const bad = run(d, 'add', 'telepathy'); assert.notEqual(bad.status, 0); assert.match(bad.stderr, /unknown plugin: telepathy/);
});

test('every plugin in the catalog is a module with that factory', async () => {
  const { PLUGINS } = await import('../plugins.mjs');
  const root = join(dirname(fileURLToPath(import.meta.url)), '..');
  for (const [name, p] of Object.entries(PLUGINS)) {
    const dir = join(root, p.module.slice(1));
    assert.ok(existsSync(join(dir, 'build.gradle.kts')), `${name}: ${p.module} exists`);
    const pkg = p.imp.slice(0, p.imp.lastIndexOf('.'));
    const src = spawnSync('git', ['grep', '-l', `^val ${p.factory} = PluginFactory`, '--', `${p.module.slice(1)}/src`], { cwd: root, encoding: 'utf8' }).stdout;
    assert.ok(src.trim(), `${name}: val ${p.factory} = PluginFactory in ${p.module.slice(1)}/src`);
    assert.match(readFileSync(join(root, src.trim().split('\n')[0]), 'utf8'), new RegExp(`^package ${pkg.replace(/\./g, '\.')}$`, 'm'), `${name}: package ${pkg}`);
  }
});
