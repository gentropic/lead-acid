// The plugin catalog: name → { module, imp, factory, what }. One list for both ways of making an instrument:
// `lead-acid init/sync` (an app in its own repo, lead-acid from npm) and pack-instrument.mjs (a module in this repo).
// A plugin's permissions are not listed here: they live in its own manifest and arrive through the dependency.
export const PLUGINS = {
  sensor: { module: ':plugin-sensor', imp: 'org.gentropic.leadacid.sensor.Sensor', factory: 'Sensor', what: 'raw and fused sensors, streamed' },
  share: { module: ':plugin-share', imp: 'org.gentropic.leadacid.share.Share', factory: 'Share', what: 'the share sheet' },
  attest: { module: ':plugin-attest', imp: 'org.gentropic.leadacid.attest.Attest', factory: 'Attest', what: 'hardware-backed signatures (StrongBox)' },
  intake: { module: ':plugin-intake', imp: 'org.gentropic.leadacid.intake.Intake', factory: 'Intake', what: 'files shared to or opened with the app' },
  gnss: { module: ':plugin-gnss', imp: 'org.gentropic.leadacid.gnss.Gnss', factory: 'Gnss', what: 'raw GNSS measurements and fixes' },
  camera: { module: ':plugin-camera', imp: 'org.gentropic.leadacid.camera.Camera', factory: 'Camera', what: 'CameraX preview and RAW capture' },
  intent: { module: ':plugin-intent', imp: 'org.gentropic.leadacid.intent.IntentHandoff', factory: 'IntentHandoff', what: 'allowlisted hand-offs to other apps (calendar, clock, map, dial…)' },
  tree: { module: ':plugin-tree', imp: 'org.gentropic.leadacid.tree.Tree', factory: 'Tree', what: 'a folder the person picks, read and written' },
  speech: { module: ':plugin-speech', imp: 'org.gentropic.leadacid.speech.Speech', factory: 'Speech', what: 'voice in, on-device recognizer only' },
  tts: { module: ':plugin-tts', imp: 'org.gentropic.leadacid.tts.Tts', factory: 'Tts', what: 'voice out, offline voices only' },
};
