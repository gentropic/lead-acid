# reflex rules — the automation engine

**Design note for reflex's rule format + its two runners.** Downstream of `reflex` (→ M2 →
first instruments). Records the shape so the eventual spec doesn't rediscover it. Companion to
`docs/reflex.md` §2 ("rules are documents").

## The founding constraint: bounded, not Turing-complete

A rule is `trigger → condition → action`, as **data**. Conditions absolutely *compute* — a rule
with no condition is just "always fire," which is useless. What they compute is a **total,
bounded expression** (comparisons, boolean logic, time-of-day math, field references, string
interpolation) — the CEL / JSONLogic / spreadsheet-formula shape: always terminates, always
yields a value, can never loop or spin.

What a rule *cannot* do is **arbitrary or unbounded computation** — loops, recursion, defining
functions, transforming a dataset. That graduates to a **worker** (the pure-function tier, lead-
acid §5.2), which a rule *hands off to* (see below).

**Why the bound is load-bearing, not puritanism:** a total bounded language is small and finite,
so it can be reimplemented *faithfully* in both JS and Kotlin and pinned together by a conformance
corpus. Allow loops/functions and you've built a programming language — and two hand-written
interpreters of a programming language *will* drift. The bound is exactly what makes "two runners,
one truth" safe. Conditions think; they just can't *ponder*.

## The stack — mostly composition, almost nothing new

| layer | package | role |
|---|---|---|
| storage | **`@gcu/yaml`** | the rule document (strict auditable subset: block sequences, quoted scalars) |
| conditions & value exprs | **`@gcu/expr`** | the friendly string calculus — CSP-safe (no eval), already shipped in lamina/micro. Does BOTH halves: conditions (`"battery.level < 15 and gnss.logging"` → boolean) *and* action value-expressions / interpolation (`${battery.level}`) — sift can't do values, expr can. **Primary.** |
| structured conditions | **`@gcu/sift`** | the pure-data predicate form for conditions you want as data, not a string (`{ and: [ { lt: [...] }, … ] }`). The canonical/machine form; expr is the human form. |
| vocabulary | *(new, tiny)* | the trigger + action descriptors — the only genuinely new code |

Three things `@gcu/expr` buys specifically:
- **CSP-safe evaluation of untrusted rules.** Rules may be shared documents (`.reflex` files handed
  between people); evaluating their conditions must be safe against a hostile string. expr already
  guarantees no-eval evaluation, so the friendly layer never compromises the Sealed posture.
- **`deps` extraction.** expr knows *which fields* a condition reads → the runner gathers only those
  inputs, and the editor validates at edit time ("references `battery.level`, but a geofence trigger
  doesn't provide that" → red underline).
- **Zero new expression language.** The concern of "reinventing a parser in two runtimes" dissolves —
  it's expr, which has a spec and a corpus to port against.

## The vocabulary (the only new part)

- **Triggers** (data descriptors, each maps to a native listener on the Kotlin side):
  `{time: {cron}}` · `{geofence: {lat, lon, radius, on: enter|exit|dwell}}` · `{wifi: {ssid}}` ·
  `{broadcast: charging|boot|bt|headphones|airplane}` · `{route: "gnss/log/start"}` (the instrument
  seam — a documented allowlisted intent).
- **Conditions**: an expr string OR a sift structure → boolean. Optional (absent = always).
- **Actions**: `{route, with}` (fire an intent at an instrument / another app) · `{notify}` ·
  `{setFlag}` · `{webhook}` (networked only) · `{worker, input, then}` (hand off to a pure worker).

## The two runners

- **Kotlin runner — the real executor** (reflex's spinal cord). Registers the *actual* native
  triggers (`GeofencingClient`, `BroadcastReceiver`, `AlarmManager`/`WorkManager`), evaluates the
  condition (ported expr/sift over the trigger context + a small flag state), fires the action as an
  intent. Runs headless via receivers / the foreground-service convention — no WebView. Fires at
  3 a.m. at the outcrop.
- **JS runner — the editor + simulator** (and interactive eval). Can't register a *real* geofence
  in a browser, and doesn't need to: it live-tests a rule in the Switchboard editor ("if this
  trigger fired at this time and place, would it fire, and do what?"), dry-runs against **the
  bench's mocked context on the desktop**, and optionally evaluates rules in-page for an interactive
  artifact. Same documents, evaluated against provided/simulated context.

## The parity oracle — the load-bearing mechanism

"Two runners, one truth" holds only if you *prove* they agree; silent drift (a rule that behaves
differently in the editor than in the field) is the worst failure. So: a **conformance corpus** of
`(rule, context) → expected {fires, actions}` cases, run through *both* runners, asserting identical
output. The corpus is the contract; the runners are two implementations satisfying it — the exact
pattern as AIR's interpreter-vs-emit-js parity test. Cheap because the semantics are one page and
expr/sift already carry their own corpora.

## The worker handoff — reflexes vs thought

A rule's action can be `{worker: "digest-day", input, then}`. So a **reflex** (declarative, no
thought — "at 02:00, run the digest") *invokes* a **worker** (the pure-function burst of thought —
"compute the mean grade per band"), whose output Kotlin applies. Rules are the spinal cord; a
worker is a reflex arc that reaches a neuron and returns. Together they are the whole background
story, and the boundary between them is exactly the bounded/unbounded line above.

## Example

```yaml
# field.reflex — the Kotlin runner fires these headless; the WebView edits them.
rules:
  - name: "log at the outcrop"
    when: { geofence: { lat: -30.05, lon: -51.17, radius: 120, on: "enter" } }
    if: "hour >= 7 and hour < 18"                 # expr string — computes
    do:
      - route: "gnss/log/start"
        with: { rate: 1, tag: "outcrop-A" }
      - notify: "logging started at ${place}"      # expr interpolation

  - name: "stop when I leave"
    when: { geofence: { lat: -30.05, lon: -51.17, radius: 120, on: "exit" } }
    do:
      - route: "gnss/log/stop"

  - name: "bail on low battery"
    when: { broadcast: "battery" }
    if: { and: [ { lt: ["battery.level", 15] }, { eq: ["gnss.logging", true] } ] }   # sift form
    do:
      - route: "gnss/log/stop"
      - notify: "stopped — battery ${battery.level}%"

  - name: "nightly digest"
    when: { time: { cron: "0 2 * * *" } }
    do:
      - worker: "digest-day"                        # hand off — real logic lives here
        input:  { from: "gnss/today" }
        then:   { publish: "digest.csv" }
```

## Build dependencies + open questions

- **Concrete build prereq: Kotlin ports of `@gcu/sift` + `@gcu/expr`.** Both total, small, CSP-safe,
  corpus-backed → bounded, verifiable ports (run the JS corpus against the Kotlin impl + the
  cross-runner conformance corpus). **Reusable beyond reflex** — any Kotlin-side GCU code wanting
  safe predicate/expression eval gets them. This is the real work; the rest is vocabulary + glue.
- **Open: expr-string canonical vs sift-canonical.** Store the friendly expr string and treat sift
  as a compile target, or store sift and treat the string as edit-time sugar? Leaning: accept both
  in the document, expr the human default; the corpus tests whichever the runners consume.
- **Open: the flag/state model.** Rules read a small persisted state (`gnss.logging`, custom flags).
  Who owns it, how it's namespaced, how a `setFlag` action + a condition read stay consistent across
  the two runners — a design beat for the spec.

---

*Geoscientific Chaos Union — CC0. Reflexes think, but they never ponder.*
