# reflex — design note

**Phone automation with reflexes, never thoughts.**

> A reflex fires through the spinal cord without the brain's involvement. So does this app: Kotlin executes standing rules while the WebView — the brain — isn't even present.

Status: **DESIGN NOTE** — pre-spec, pre-implementation. Downstream of `@gcu/lead-acid` (M2+) and the first shipped instruments. This document records the founding argument so the eventual spec doesn't have to rediscover it.

---

## 1. The founding argument: restraint as the feature

Tasker-class automation apps live in permanent probation with Google because their maximalist capability set — reading notifications, watching foregrounded apps, injecting input, accessibility services — is indistinguishable at the API level from stalkerware. The category is reviewed by exception and survives policy purges by attrition.

reflex is built from the observation that the radioactive tier is *severable*. What remains is rich and entirely policy-clean:

**Triggers that need no scary permissions:**
- Time and calendar-window scheduling (AlarmManager/WorkManager — no permission)
- Geofencing via platform GeofencingClient (location permission with a self-evident purpose: a location-automation app asking for location raises zero eyebrows)
- Wi-Fi connect/disconnect and SSID (location permission again, same honest framing — reflex pays this cost so the instruments never do)
- Charging state, power connect, headphones, boot completed, airplane mode, Bluetooth device connect — plain broadcasts any app may receive

**Actions:** firing intents at other apps. That is the entire output side, and it is the polite, documented, beloved half of the automation ecosystem.

**The permanent refusals** (load-bearing — the day any of these is accepted, the category changes and review treatment follows):
- No notification reading. No screen reading. No foreground-app detection.
- No input injection. No accessibility services, ever, for any reason.
- The answer to "trigger on notification from app X" is no, forever.

The pitch is the same move as RelayKVM-as-keyboard: an automation app that *provably cannot* see your notifications or screen, because you can read all of it.

## 2. Rules are documents

Automation rules are the artifact's content: trigger → condition → action, declarative, no computation. Stored as `@gcu/yaml` (strict auditable subset) documents. The rule language's entire semantics must fit on one page — that is what keeps rules *auditable* rather than merely stored.

- **Kotlin executes rules but does not interpret a language.** Rules are parsed once and evaluated as data. Badge work is Kotlin; the rule engine is trigger listeners (geofence callbacks, broadcast receivers — dumb, reliable) plus an intent-firing action executor. Roughly three plugins on lead-acid.
- **The WebView is the rule *editor*** — a Switchboard surface for composing, reading, and testing rules. The brain designs the reflex arc; the spinal cord runs it.
- The moment a rule wants *logic* — transform content, decide based on data — it has graduated beyond a reflex. That work belongs to an instrument's worker or interactive flow (lead-acid §5.2–5.3), invoked *by* a reflex, never embedded *in* one.

## 3. The ecosystem seam

reflex + the Home Assistant companion app cover Tasker's territory split along the correct seam:

- **Phone-local** automation (geofence → start GNSS log) lives in reflex, with auditable rules.
- **Cross-device** orchestration (phone arrived home → NAS starts backup → light turns on) lives in Home Assistant, where it belongs.
- The two compose in both directions: a reflex action may fire a webhook at HA; an HA automation may reach an instrument through documented intents.

Instruments participate via the lead-acid automation-broadcast convention (one generic receiver mapping documented intents onto the plugin dispatch — see lead-acid design-log note). reflex is the first-party conductor for those levers; it holds the radioactive-adjacent permissions (location for geofencing/SSID) exactly once, so every instrument's data-safety form stays a column of No. GCU on both ends of the intent.

## 4. Boundary notes for the eventual spec

- Exported receivers are callable by any app: instrument-side generic receivers route only an **allowlist** of automation-safe routes ("start log" yes; arbitrary `fs` reads no).
- Intents that start foreground services from the background rely on Android's explicit carve-outs — verification item when implementation begins.
- Rule execution while the app is dead is the entire point: the engine is a boot-registered, badge-carrying citizen of lead-acid's foreground-service convention where continuity demands it, and a plain receiver where it doesn't.
- Naming lineage, for the record: *geas* was the semantically perfect name (a magically binding obligation that compels behavior) but is spent on the GCU POSIX shell; *geasd* was a good bit (the geas daemon) but names were conserved; *harness* was captured by AI-industry discourse; **reflex** won on dictionary self-evidence and docs-sentence load-bearing: "reflexes fire without the app open."

---

*Geoscientific Chaos Union — CC0. This app will never think. That is a promise.*
