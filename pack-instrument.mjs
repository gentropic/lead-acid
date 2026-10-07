#!/usr/bin/env node
// pack-instrument — the five-minute recipe as one command (PLAN M2 Phase 3).
//
//   node pack-instrument.mjs <module> --id <applicationId> --label "<name>"
//                            [--artifact <built.html>] [--plugins sensor,share,attest,intake]
//                            [--intake text/csv,text/plain]   (mime types the instrument takes in)
//
// Stamps a new instrument module out of template/: namespace + applicationId,
// label, the plugin list (both the Gradle dependencies AND the activity's
// factory list — the two places that must agree), the artifact, and the
// settings.gradle.kts include. Re-running on the same module regenerates the
// stamped files in place (replace on name); an existing artifact is kept unless
// --artifact is given again. Zero dependencies; node ≥ 18.
//
// After it runs:  gradlew.bat :<module>:assembleDebug
import { cpSync, existsSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = dirname(fileURLToPath(import.meta.url));
const TEMPLATE = join(ROOT, 'template');

// ── plugin catalog: name → { module, import, factory } ──────────────────────
const PLUGINS = {
  sensor: { module: ':plugin-sensor', imp: 'org.gentropic.leadacid.sensor.Sensor', factory: 'Sensor' },
  share: { module: ':plugin-share', imp: 'org.gentropic.leadacid.share.Share', factory: 'Share' },
  attest: { module: ':plugin-attest', imp: 'org.gentropic.leadacid.attest.Attest', factory: 'Attest' },
  intake: { module: ':plugin-intake', imp: 'org.gentropic.leadacid.intake.Intake', factory: 'Intake' },
};

// ── args ─────────────────────────────────────────────────────────────────────
const args = process.argv.slice(2);
const flags = {};
const positional = [];
for (let i = 0; i < args.length; i++) {
  const a = args[i];
  if (a.startsWith('--')) { flags[a.slice(2)] = args[i + 1]; i++; } else positional.push(a);
}
const name = positional[0];
const usage = () => {
  console.error('usage: node pack-instrument.mjs <module> --id <applicationId> --label "<name>" [--artifact <built.html>] [--plugins sensor,share,attest,intake] [--intake <mime,mime>]');
  process.exit(2);
};
if (!name || !flags.id || !flags.label) usage();
if (!/^[a-z][a-z0-9-]*$/.test(name)) { console.error(`module name must be [a-z][a-z0-9-]*: ${name}`); process.exit(2); }
if (!/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(flags.id)) { console.error(`applicationId must be a dotted lowercase package: ${flags.id}`); process.exit(2); }
if (['core', 'app', 'template', 'wuffle'].includes(name) || name.startsWith('plugin-')) { console.error(`${name} is a reserved module name`); process.exit(2); }
const plugins = (flags.plugins || '').split(',').map((s) => s.trim()).filter(Boolean);
for (const p of plugins) if (!PLUGINS[p]) { console.error(`unknown plugin: ${p} (have: ${Object.keys(PLUGINS).join(', ')})`); process.exit(2); }

const DEST = join(ROOT, name);
const fresh = !existsSync(DEST);

// ── 1. copy the template (sources + icon; never its build/) ─────────────────
cpSync(join(TEMPLATE, 'src'), join(DEST, 'src'), {
  recursive: true,
  filter: (src) => !src.includes(`${join('src', 'main', 'java')}`) && !(src.endsWith('artifact.html') && !fresh && !flags.artifact),
});

const read = (p) => readFileSync(p, 'utf8');
const write = (p, s) => { mkdirSync(dirname(p), { recursive: true }); writeFileSync(p, s); };
const must = (s, old, what) => { if (!s.includes(old)) throw new Error(`template drifted: ${what} not found`); return s; };

// ── 2. build.gradle.kts: id, deps, no sync task (artifact is a plain file) ──
let gradle = read(join(TEMPLATE, 'build.gradle.kts'));
gradle = must(gradle, 'org.gentropic.leadacid.template', 'template id').replaceAll('org.gentropic.leadacid.template', flags.id);
const depAnchor = `    // One plugin, to show the shape. Add :plugin-sensor / :plugin-attest / …
    // here AND in InstrumentActivity.plugins; remove both to drop a capability.
    implementation(project(":plugin-share"))
`;
const depLines = plugins.map((p) => `    implementation(project("${PLUGINS[p].module}"))`).join('\n');
gradle = must(gradle, depAnchor, 'template dependency block').replace(
  depAnchor,
  (plugins.length ? `    // Plugins: each one here must also be listed in InstrumentActivity.plugins.\n${depLines}\n`
    : `    // No plugin modules: this instrument uses only what core provides.\n`),
);
gradle = gradle.replace(/^\/\/ template — the copy-me instrument\.[\s\S]*?\n(?=import|plugins)/, `// ${name} — a lead-acid instrument, stamped from template/ by pack-instrument.mjs.\n`);
write(join(DEST, 'build.gradle.kts'), gradle);

// ── 3. manifest: label ───────────────────────────────────────────────────────
let manifest = read(join(TEMPLATE, 'src', 'main', 'AndroidManifest.xml'));
manifest = must(manifest, 'android:label="lead-acid instrument"', 'template label')
  .replace('android:label="lead-acid instrument"', `android:label="${flags.label.replace(/"/g, '&quot;')}"`);
// --intake: the commented share-target example becomes a real filter with these mime types
const intakeMimes = (flags.intake || '').split(',').map((s) => s.trim()).filter(Boolean);
if (intakeMimes.length && !plugins.includes('intake')) { console.error('--intake needs the intake plugin: add it to --plugins'); process.exit(2); }
manifest = manifest.replace(/\n *<!-- To take files in from the share sheet[\s\S]*?-->\n/, intakeMimes.length
  ? `\n            <!-- intake: shared / opened files reach the artifact -->\n            <intent-filter>\n                <action android:name="android.intent.action.SEND" />\n                <action android:name="android.intent.action.SEND_MULTIPLE" />\n                <action android:name="android.intent.action.VIEW" />\n                <category android:name="android.intent.category.DEFAULT" />\n${intakeMimes.map((m) => `                <data android:mimeType="${m}" />`).join('\n')}\n            </intent-filter>\n`
  : '\n');
write(join(DEST, 'src', 'main', 'AndroidManifest.xml'), manifest);

// ── 4. the activity: package, imports, factory list ─────────────────────────
let activity = read(join(TEMPLATE, 'src', 'main', 'java', 'org', 'gentropic', 'leadacid', 'template', 'InstrumentActivity.kt'));
activity = must(activity, 'package org.gentropic.leadacid.template', 'template package')
  .replace('package org.gentropic.leadacid.template', `package ${flags.id}`);
activity = must(activity, 'import org.gentropic.leadacid.share.Share\n', 'template import')
  .replace('import org.gentropic.leadacid.share.Share\n', plugins.map((p) => `import ${PLUGINS[p].imp}\n`).join(''));
activity = must(activity, 'listOf(Share)', 'template factory list')
  .replace('listOf(Share)', plugins.length ? `listOf(${plugins.map((p) => PLUGINS[p].factory).join(', ')})` : 'emptyList()');
rmSync(join(DEST, 'src', 'main', 'java'), { recursive: true, force: true });
write(join(DEST, 'src', 'main', 'java', ...flags.id.split('.'), 'InstrumentActivity.kt'), activity);

// ── 5. the artifact ──────────────────────────────────────────────────────────
if (flags.artifact) {
  const src = resolve(flags.artifact);
  if (!existsSync(src)) { console.error(`artifact not found: ${src}`); process.exit(2); }
  cpSync(src, join(DEST, 'src', 'main', 'assets', 'artifact.html'));
}

// ── 6. settings.gradle.kts include ───────────────────────────────────────────
const settingsPath = join(ROOT, 'settings.gradle.kts');
let settings = read(settingsPath);
if (!settings.includes(`":${name}"`)) {
  settings = must(settings, 'include(', 'settings include').replace(/include\(([^)]*)\)/, (m, inner) => `include(${inner}, ":${name}")`);
  writeFileSync(settingsPath, settings);
}

console.log(`${fresh ? 'created' : 'regenerated'} ${name}/ — ${flags.id} "${flags.label}"${plugins.length ? ` with ${plugins.join(', ')}` : ' (core only)'}`);
console.log(`artifact: ${flags.artifact ? resolve(flags.artifact) : (fresh ? 'the template placeholder — replace src/main/assets/artifact.html' : 'kept')}`);
console.log(`next:     gradlew.bat :${name}:assembleDebug`);
