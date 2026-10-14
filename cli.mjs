#!/usr/bin/env node
// lead-acid — an Android app around a single-file web artifact, in the artifact's own repo.
//
//   npm install --save-dev @gcu/lead-acid
//   npx lead-acid init --id org.example.thing --label "thing" --artifact dist/thing.html [--plugins intent,share]
//                      [--intake text/csv,text/plain] [--dir android] [--leadacid <path to lead-acid>]
//   npx lead-acid build [--release | --bundle]        → gradlew in android/, then the APK's path
//   npx lead-acid add <plugin…> | remove <plugin…>     → edits lead-acid.json, then syncs
//   npx lead-acid sync                                 → rewrites the generated files from lead-acid.json
//   npx lead-acid plugins                              → the catalog, with the permissions each one brings
//
// `init` writes android/ once: the Gradle files, the manifest, the activity and the icons are yours from then on.
// What `sync` owns is three small files, each saying so in its first line: android/leadacid.settings.gradle.kts
// (lead-acid's modules, built from source where `leadacid.dir` points: this package in node_modules, or a clone),
// android/app/leadacid.gradle.kts (the plugin dependencies and the artifact copied in at every build) and
// LeadAcidPlugins.kt (the same plugins as factories). The dependency and the factory list are the two places that
// must agree; writing both from one list is the point. lead-acid's own modules build under android/build/leadacid/,
// never inside node_modules or the clone. Zero dependencies; node ≥ 18.
import { copyFileSync, chmodSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync, cpSync, realpathSync } from 'node:fs';
import { dirname, join, relative, resolve, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { PLUGINS } from './plugins.mjs';

const ROOT = dirname(fileURLToPath(import.meta.url));
const TEMPLATE = join(ROOT, 'template');
const CONFIG = 'lead-acid.json';
const GENERATED = '// Written by `lead-acid sync` from lead-acid.json: do not edit, run the sync again.';

const die = (msg) => { console.error(`lead-acid: ${msg}`); process.exit(2); };
const read = (p) => readFileSync(p, 'utf8');
const write = (p, s) => { mkdirSync(dirname(p), { recursive: true }); writeFileSync(p, s); };
const must = (s, old, what) => { if (!s.includes(old)) die(`the template drifted: ${what} not found (lead-acid ${version()})`); return s; };
const slash = (p) => p.split('\\').join('/');
const version = () => JSON.parse(read(join(ROOT, 'package.json'))).version;

const argv = process.argv.slice(2), cmd = argv[0], flags = {}, words = [];
for (let i = 1; i < argv.length; i++) {
  const a = argv[i];
  if (!a.startsWith('--')) { words.push(a); continue; }
  const next = argv[i + 1];
  if (next === undefined || next.startsWith('--')) flags[a.slice(2)] = true; else { flags[a.slice(2)] = next; i++; }
}
const list = (s) => (typeof s === 'string' ? s : '').split(',').map((x) => x.trim()).filter(Boolean);
const checkPlugins = (ps) => { for (const p of ps) if (!PLUGINS[p]) die(`unknown plugin: ${p} (have: ${Object.keys(PLUGINS).join(', ')})`); return ps; };

const project = process.cwd();
const loadConfig = () => {
  const p = join(project, CONFIG);
  if (!existsSync(p)) die(`no ${CONFIG} here: run \`lead-acid init\` in the project's root first`);
  const c = JSON.parse(read(p));
  if (!c.id || !c.artifact || !Array.isArray(c.plugins)) die(`${CONFIG} needs id, artifact and plugins`);
  return { dir: 'android', ...c };
};
const saveConfig = (c) => write(join(project, CONFIG), JSON.stringify(c, null, 2) + '\n');

// ── the generated files ──────────────────────────────────────────────────────
function sync(c) {
  checkPlugins(c.plugins);
  if (c.intake && !c.plugins.includes('intake')) console.warn('lead-acid: the manifest takes files in, but the intake plugin is not listed');
  const android = join(project, c.dir), app = join(android, 'app');
  if (!existsSync(join(app, 'build.gradle.kts'))) die(`${c.dir}/app is missing: run \`lead-acid init\``);
  const modules = ['core', ...c.plugins.map((p) => PLUGINS[p].module.slice(1))];
  write(join(android, 'leadacid.settings.gradle.kts'), `${GENERATED}
// lead-acid's modules, built from source where \`leadacid.dir\` (gradle.properties) points: the npm package or a clone.
val leadacidDir = settingsDir.resolve(providers.gradleProperty("leadacid.dir").get()).canonicalFile
if (!leadacidDir.resolve("core/build.gradle.kts").exists())
    throw GradleException("lead-acid not found at $leadacidDir: run npm install, or set leadacid.dir in gradle.properties")

dependencyResolutionManagement {
    // lead-acid's versions (AGP, Kotlin, AndroidX, the SDK levels) are this app's: they move together
    versionCatalogs { create("libs") { from(files(leadacidDir.resolve("gradle/libs.versions.toml"))) } }
}

for (m in listOf(${modules.map((m) => `"${m}"`).join(', ')})) {
    include(":$m")
    project(":$m").projectDir = leadacidDir.resolve(m)
}
`);
  const artifact = slash(relative(android, resolve(project, c.artifact)));
  write(join(app, 'leadacid.gradle.kts'), `${GENERATED}
// The plugins: each module brings its own permissions into the merged manifest; LeadAcidPlugins.kt lists the same.
dependencies {
${modules.map((m) => `    "implementation"(project(":${m}"))`).join('\n')}
}

// The artifact, copied from the web build at every build (never committed under src/): ${c.artifact}
val leadacidArtifact = rootProject.file("${artifact}")
val leadacidCopy = tasks.register("leadacidArtifact") {
    val out = layout.buildDirectory.dir("generated/leadacid-artifact")
    inputs.files(leadacidArtifact)
    outputs.dir(out)
    doLast {
        if (!leadacidArtifact.exists()) throw GradleException("the artifact is not built: $leadacidArtifact (${c.artifact} in lead-acid.json)")
        val dir = out.get().asFile
        dir.mkdirs()
        leadacidArtifact.copyTo(dir.resolve("artifact.html"), overwrite = true)
    }
}
tasks.matching { it.name.startsWith("generate") || it.name.startsWith("merge") }.configureEach { dependsOn(leadacidCopy) }
`);
  write(join(app, 'src', 'main', 'java', ...c.id.split('.'), 'LeadAcidPlugins.kt'), `${GENERATED}
package ${c.id}

import org.gentropic.leadacid.PluginFactory
${c.plugins.map((p) => `import ${PLUGINS[p].imp}\n`).join('')}
/** The plugin factories, for the modules app/leadacid.gradle.kts depends on (\`fs\` and \`shell\` are always there). */
val leadAcidPlugins: List<PluginFactory> = ${c.plugins.length ? `listOf(${c.plugins.map((p) => PLUGINS[p].factory).join(', ')})` : 'emptyList()'}
`);
  console.log(`synced ${c.dir}/: ${c.plugins.length ? c.plugins.join(', ') : 'no plugins (core only)'}, artifact ${c.artifact}`);
}

// ── init: android/, written once ─────────────────────────────────────────────
function init() {
  const { id, label, artifact } = flags;
  if (typeof id !== 'string' || typeof label !== 'string' || typeof artifact !== 'string')
    die('usage: lead-acid init --id <applicationId> --label "<name>" --artifact <built.html> [--plugins a,b] [--intake <mime,mime>] [--dir android] [--leadacid <path>]');
  if (!/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(id)) die(`applicationId must be a dotted lowercase package: ${id}`);
  if (existsSync(join(project, CONFIG))) die(`${CONFIG} exists: this project is set up (use sync, add or remove)`);
  const dir = typeof flags.dir === 'string' ? flags.dir : 'android', android = join(project, dir), app = join(android, 'app');
  if (existsSync(android)) die(`${dir}/ exists: init writes it once and does not overwrite`);
  const plugins = checkPlugins(list(flags.plugins)), intake = list(flags.intake);
  if (intake.length && !plugins.includes('intake')) die('--intake needs the intake plugin: add it to --plugins');
  const lib = typeof flags.leadacid === 'string' ? resolve(flags.leadacid) : realpathSync(ROOT);
  if (!existsSync(join(lib, 'core', 'build.gradle.kts'))) die(`lead-acid not found at ${lib}`);
  const libRel = isAbsolute(relative(android, lib)) ? slash(lib) : slash(relative(android, lib));

  // the wrapper: the Gradle lead-acid builds with
  for (const f of ['gradlew', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties']) {
    mkdirSync(dirname(join(android, f)), { recursive: true }); copyFileSync(join(ROOT, f), join(android, f));
  }
  // LF whatever the checkout or the packing machine did to it: a CRLF gradlew does not run on macOS or Linux
  writeFileSync(join(android, 'gradlew'), read(join(android, 'gradlew')).replace(/\r\n/g, '\n'));
  chmodSync(join(android, 'gradlew'), 0o755);
  const name = label.replace(/[^A-Za-z0-9 _-]/g, '').trim() || 'app';
  write(join(android, 'settings.gradle.kts'), `// Written once by \`lead-acid init\`; yours to edit.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "${name}"
include(":app")
apply(from = "leadacid.settings.gradle.kts")   // lead-acid's core and plugins (written by \`lead-acid sync\`)
`);
  write(join(android, 'build.gradle.kts'), `// Written once by \`lead-acid init\`; yours to edit.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// lead-acid's modules live outside this folder (node_modules, or a clone): they build here, not there.
subprojects {
    if (!projectDir.toPath().normalize().startsWith(rootDir.toPath().normalize()))
        layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("leadacid/$name"))
}
`);
  write(join(android, 'gradle.properties'), `org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true

# Where lead-acid is: the npm package (npm install --save-dev @gcu/lead-acid), or a clone of gentropic/lead-acid.
leadacid.dir=${libRel}
`);
  write(join(android, '.gitignore'), `.gradle/
.kotlin/
build/
local.properties
# Signing: the keystore never enters a repo (lead-acid docs/signing.md)
keystore.properties
*.keystore
*.jks
`);
  const sdk = [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT, process.env.LOCALAPPDATA && join(process.env.LOCALAPPDATA, 'Android', 'Sdk'),
    process.env.HOME && join(process.env.HOME, 'Library', 'Android', 'sdk'), process.env.HOME && join(process.env.HOME, 'Android', 'Sdk')].find((p) => p && existsSync(p));
  if (sdk) write(join(android, 'local.properties'), `sdk.dir=${slash(sdk)}\n`);

  // app/: the template, with this app's id, label and activity
  let gradle = read(join(TEMPLATE, 'build.gradle.kts'));
  gradle = must(gradle, 'org.gentropic.leadacid.template', 'template id').replaceAll('org.gentropic.leadacid.template', id);
  gradle = gradle.replace(/^\/\/ template — the copy-me instrument\.[\s\S]*?\n(?=import|plugins)/, `// ${name}: the app around ${artifact}. Written once by \`lead-acid init\`; yours to edit (the version, signing).\n`);
  gradle = must(gradle, '    compileOptions {', 'template compileOptions').replace('    compileOptions {',
    `    // the artifact, copied from the web build at every build (leadacid.gradle.kts)
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/leadacid-artifact"))

    compileOptions {`);
  gradle = gradle.replace(/\ndependencies \{[\s\S]*?\n\}\n/, '\n// lead-acid\'s core, the plugins and the artifact (written by `lead-acid sync` from lead-acid.json)\napply(from = "leadacid.gradle.kts")\n');
  must(gradle, 'apply(from = "leadacid.gradle.kts")', 'template dependencies block');
  write(join(app, 'build.gradle.kts'), gradle);

  let manifest = read(join(TEMPLATE, 'src', 'main', 'AndroidManifest.xml'));
  manifest = must(manifest, 'android:label="lead-acid instrument"', 'template label').replace('android:label="lead-acid instrument"', `android:label="${label.replace(/&/g, '&amp;').replace(/"/g, '&quot;')}"`);
  manifest = manifest.replace(/\n *<!-- To take files in from the share sheet[\s\S]*?-->\n/, intake.length
    ? `\n            <!-- intake: shared / opened files reach the artifact -->\n            <intent-filter>\n                <action android:name="android.intent.action.SEND" />\n                <action android:name="android.intent.action.SEND_MULTIPLE" />\n                <action android:name="android.intent.action.VIEW" />\n                <category android:name="android.intent.category.DEFAULT" />\n${intake.map((m) => `                <data android:mimeType="${m}" />`).join('\n')}\n            </intent-filter>\n`
    : '\n');
  write(join(app, 'src', 'main', 'AndroidManifest.xml'), manifest);
  cpSync(join(TEMPLATE, 'src', 'main', 'res'), join(app, 'src', 'main', 'res'), { recursive: true });
  write(join(app, 'src', 'main', 'java', ...id.split('.'), 'InstrumentActivity.kt'), `package ${id}

import org.gentropic.leadacid.LeadAcidActivity
import org.gentropic.leadacid.PluginFactory

/**
 * The app is a LeadAcidActivity and its list of plugins. The list is generated (LeadAcidPlugins.kt, from
 * lead-acid.json) beside the Gradle dependencies that carry each plugin's permissions, so the two agree.
 */
class InstrumentActivity : LeadAcidActivity() {
    override val plugins: List<PluginFactory> = leadAcidPlugins
}
`);
  const c = { id, artifact: slash(artifact), plugins, dir: slash(dir), ...(intake.length ? { intake } : {}) };
  saveConfig(c);
  console.log(`created ${dir}/ for ${id} "${label}" (lead-acid ${version()} at ${libRel})`);
  sync(c);
  if (!sdk) console.log('no Android SDK found: set sdk.dir in ' + slash(join(dir, 'local.properties')) + ' or ANDROID_HOME');
  console.log(`next: build ${artifact}, then \`npx lead-acid build\``);
}

// ── build: gradlew, with a JDK found if JAVA_HOME is not set ────────────────
function build() {
  const c = loadConfig(), android = join(project, c.dir);
  if (!existsSync(resolve(project, c.artifact))) die(`the artifact is not built: ${c.artifact}`);
  const env = { ...process.env };
  if (!env.JAVA_HOME) {
    const jbr = ['C:/Program Files/Android/Android Studio/jbr', '/Applications/Android Studio.app/Contents/jbr/Contents/Home', '/opt/android-studio/jbr'].find((p) => existsSync(p));
    if (jbr) { env.JAVA_HOME = jbr; console.log(`JAVA_HOME: ${jbr} (Android Studio's)`); }
  }
  const task = flags.bundle ? 'bundleRelease' : flags.release ? 'assembleRelease' : 'assembleDebug';
  const win = process.platform === 'win32';
  // a .bat runs through cmd.exe, named here rather than through spawn's shell option (which concatenates the arguments)
  const args = [`:app:${task}`, ...words];
  const r = win ? spawnSync(env.ComSpec || 'cmd.exe', ['/d', '/c', join(android, 'gradlew.bat'), ...args], { cwd: android, env, stdio: 'inherit' })
    : spawnSync('./gradlew', args, { cwd: android, env, stdio: 'inherit' });
  if (r.status !== 0) process.exit(r.status || 1);
  const out = join(android, 'app', 'build', 'outputs', flags.bundle ? 'bundle' : 'apk');
  const found = [];
  const walk = (d) => { if (existsSync(d)) for (const e of readdirSync(d, { withFileTypes: true })) e.isDirectory() ? walk(join(d, e.name)) : /\.(apk|aab)$/.test(e.name) && found.push(join(d, e.name)); };
  walk(out);
  for (const f of found) console.log(slash(relative(project, f)));
}

function plugins() {
  for (const [n, p] of Object.entries(PLUGINS)) {
    const m = join(ROOT, p.module.slice(1), 'src', 'main', 'AndroidManifest.xml');
    const perms = existsSync(m) ? [...read(m).matchAll(/uses-permission[^>]*android:name="android\.permission\.([A-Z_]+)"/g)].map((x) => x[1]) : [];
    console.log(`${n.padEnd(8)} ${p.what}${perms.length ? `\n         permissions: ${perms.join(', ')}` : ''}`);
  }
}

if (cmd === 'init') init();
else if (cmd === 'sync') sync(loadConfig());
else if (cmd === 'add' || cmd === 'remove') {
  const c = loadConfig(), ps = checkPlugins(words);
  if (!ps.length) die(`usage: lead-acid ${cmd} <plugin…>`);
  c.plugins = cmd === 'add' ? [...new Set([...c.plugins, ...ps])] : c.plugins.filter((p) => !ps.includes(p));
  saveConfig(c); sync(c);
} else if (cmd === 'build') build();
else if (cmd === 'plugins') plugins();
else if (cmd === '--version' || cmd === 'version') console.log(version());
else die(`usage: lead-acid <init | sync | add | remove | build | plugins | version>  (lead-acid ${version()})`);
