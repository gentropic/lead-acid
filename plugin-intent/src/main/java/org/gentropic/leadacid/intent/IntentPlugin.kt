package org.gentropic.leadacid.intent

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import org.gentropic.leadacid.BridgeRequest
import org.gentropic.leadacid.BridgeResponse
import org.gentropic.leadacid.Plugin
import org.gentropic.leadacid.PluginFactory
import org.gentropic.leadacid.PluginHost
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Allowlisted hand-offs to other apps (spec: intent-plugin-spec.md). The page
 * names ONE of a fixed list and gives typed fields; the shell builds the
 * Android intent itself and starts the other app, where the person confirms.
 * The page never passes an action, a component, a scheme or a raw extra —
 * that is the whole security argument, and why this plugin is small.
 *
 *   GET  /native/intent/list      → { "<name>": canHandle, … } (resolveActivity under <queries>;
 *                                    sendto reports sendto.sms and sendto.mail separately)
 *   POST /native/intent/<name>    body: JSON fields → { launched: true }
 *       400 {error, field}         a field outside its type / range / length, or unknown
 *       404                        a name not on the allowlist
 *       503 {error, capability}    no app on this phone handles it
 *       500 {error, detail}        startActivity threw
 *
 * No result lane: the other app does not report what the person did.
 * Permission: only `com.android.alarm.permission.SET_ALARM` (install-time),
 * declared by THIS module's manifest together with the <queries> block.
 */
class IntentPlugin(private val host: PluginHost) : Plugin {
    override val name = "intent"

    override fun handle(req: BridgeRequest): BridgeResponse {
        if (req.subpath == "list") {
            if (req.method != "GET") return BridgeResponse.error(405, "Method Not Allowed", req.method)
            return list()
        }
        val spec = ALLOW[req.subpath] ?: return BridgeResponse.error(404, "Not Found", "intent/${req.subpath}")
        if (req.method != "POST") return BridgeResponse.error(405, "Method Not Allowed", req.method)
        val body = req.body ?: ByteArray(0)
        val obj = try { JSONObject(if (body.isEmpty()) "{}" else String(body, Charsets.UTF_8)) }
        catch (e: JSONException) { return bad("", "body is not a JSON object") }
        val fields = HashMap<String, Any>()
        for (key in obj.keys()) {
            val f = spec.fields.firstOrNull { it.name == key } ?: return bad(key, "unknown field")
            val v = obj.get(key)
            if (v == JSONObject.NULL) continue
            val parsed = f.parse(v) ?: return bad(key, f.expects())
            fields[key] = parsed
        }
        for (f in spec.fields) if (f.required && !fields.containsKey(f.name)) return bad(f.name, "required")
        val intent = try { spec.build(fields) } catch (e: IllegalArgumentException) { return bad(e.message ?: "", "invalid") }
        if (!canHandle(intent)) return BridgeResponse.json(503, "Not Available",
            """{"error":"no app handles this","capability":"intent.${spec.name}"}""")
        var err: Throwable? = null
        val latch = CountDownLatch(1)
        host.activity.runOnUiThread {
            try { host.activity.startActivity(intent) } catch (e: Throwable) { err = e }
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        val e = err
        return if (e == null) BridgeResponse.json(200, "OK", """{"launched":true}""")
        else BridgeResponse.error(500, "Launch Failed", e.message ?: e.javaClass.simpleName)
    }

    private fun bad(field: String, msg: String) =
        BridgeResponse.json(400, "Bad Request", """{"error":${BridgeResponse.jsonStr(msg)},"field":${BridgeResponse.jsonStr(field)}}""")

    /** The allowlist with, per name, whether some installed app handles it. */
    private fun list(): BridgeResponse {
        val sb = StringBuilder("{")
        var first = true
        for ((name, spec) in ALLOW) {
            for ((label, probe) in spec.probes()) {
                if (!first) sb.append(','); first = false
                sb.append(BridgeResponse.jsonStr(label)).append(':').append(canHandle(probe))
            }
        }
        sb.append('}')
        return BridgeResponse.json(200, "OK", sb.toString())
    }

    @Suppress("DEPRECATION")
    private fun canHandle(intent: Intent): Boolean = try {
        host.context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
    } catch (e: Exception) { false }

    // ── the schema ──────────────────────────────────────────────────────────────

    /** One typed field. `parse` returns the normalized value or null when it fails. */
    abstract class Field(val name: String, val required: Boolean) {
        abstract fun parse(v: Any): Any?
        abstract fun expects(): String
    }
    class Str(name: String, required: Boolean, private val max: Int) : Field(name, required) {
        override fun parse(v: Any): Any? {
            if (v !is String) return null
            val s = v.replace(Regex("[\\p{Cntrl}]"), "").trim()
            return if (s.length > max || (required && s.isEmpty())) null else s
        }
        override fun expects() = "a string of at most $max characters"
    }
    class Int_(name: String, required: Boolean, private val lo: Long, private val hi: Long) : Field(name, required) {
        override fun parse(v: Any): Any? {
            val n = (v as? Number)?.toLong() ?: (v as? String)?.toLongOrNull() ?: return null
            if (v is Double && v != Math.floor(v)) return null
            return if (n < lo || n > hi) null else n
        }
        override fun expects() = "an integer from $lo to $hi"
    }
    class Num(name: String, required: Boolean, private val lo: Double, private val hi: Double) : Field(name, required) {
        override fun parse(v: Any): Any? {
            val n = (v as? Number)?.toDouble() ?: (v as? String)?.toDoubleOrNull() ?: return null
            return if (n.isNaN() || n < lo || n > hi) null else n
        }
        override fun expects() = "a number from $lo to $hi"
    }
    class Bool(name: String) : Field(name, false) {
        override fun parse(v: Any): Any? = v as? Boolean
        override fun expects() = "true or false"
    }
    /** ISO 8601 local date-time ("2026-10-09T09:00"; an offset is honoured when given) → epoch ms in the device zone. */
    class When(name: String, required: Boolean) : Field(name, required) {
        override fun parse(v: Any): Any? {
            val s = (v as? String)?.trim() ?: return null
            return try { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            catch (e: DateTimeParseException) {
                try { LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }   // a DST gap resolves forward
                catch (e2: DateTimeParseException) {
                    try { java.time.LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
                    catch (e3: DateTimeParseException) { null }
                }
            }
        }
        override fun expects() = "an ISO 8601 date-time, local (2026-10-09T09:00) or with an offset"
    }
    /** Days of the week as java.util.Calendar constants: 1 = Sunday … 7 = Saturday. */
    class Days(name: String) : Field(name, false) {
        override fun parse(v: Any): Any? {
            val arr = v as? JSONArray ?: return null
            val out = ArrayList<Int>()
            for (i in 0 until arr.length()) { val d = arr.optInt(i, -1); if (d < 1 || d > 7) return null; if (!out.contains(d)) out.add(d) }
            return if (out.isEmpty()) null else out
        }
        override fun expects() = "an array of days 1 (Sunday) to 7 (Saturday)"
    }
    class Enum_(name: String, required: Boolean, private val values: List<String>) : Field(name, required) {
        override fun parse(v: Any): Any? = (v as? String)?.trim()?.takeIf { values.contains(it) }
        override fun expects() = "one of ${values.joinToString(", ")}"
    }
    /** Digits, +, spaces, -, ( ) — no * or # (USSD and secret codes stay out, deliberately). */
    class Phone(name: String, required: Boolean) : Field(name, required) {
        override fun parse(v: Any): Any? {
            val s = (v as? String)?.trim() ?: return null
            return if (s.isEmpty() || s.length > 32 || !Regex("[0-9+ ()-]+").matches(s)) null else s
        }
        override fun expects() = "a phone number: digits, +, spaces, -, ( ) — at most 32 characters"
    }

    /** One allowlisted hand-off: its fields, how the intent is built, and what to probe for `list`. */
    class Spec(val name: String, val fields: List<Field>, val build: (Map<String, Any>) -> Intent, val probes: () -> List<Pair<String, Intent>>)

    companion object {
        private fun str(m: Map<String, Any>, k: String) = m[k] as? String
        private fun long(m: Map<String, Any>, k: String) = m[k] as? Long

        private fun calendar(m: Map<String, Any>): Intent {
            val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            i.putExtra(CalendarContract.Events.TITLE, str(m, "title"))
            long(m, "begin")?.let { i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
            long(m, "end")?.let { i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
            (m["allDay"] as? Boolean)?.let { i.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, it) }
            str(m, "description")?.let { i.putExtra(CalendarContract.Events.DESCRIPTION, it) }
            str(m, "location")?.let { i.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            return i
        }
        private fun timer(m: Map<String, Any>): Intent {
            val i = Intent(AlarmClock.ACTION_SET_TIMER)
            i.putExtra(AlarmClock.EXTRA_LENGTH, (long(m, "seconds") ?: 0L).toInt())
            str(m, "message")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            i.putExtra(AlarmClock.EXTRA_SKIP_UI, (m["skipUi"] as? Boolean) ?: false)
            return i
        }
        private fun alarm(m: Map<String, Any>): Intent {
            val i = Intent(AlarmClock.ACTION_SET_ALARM)
            i.putExtra(AlarmClock.EXTRA_HOUR, (long(m, "hour") ?: 0L).toInt())
            i.putExtra(AlarmClock.EXTRA_MINUTES, (long(m, "minutes") ?: 0L).toInt())
            str(m, "message")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            @Suppress("UNCHECKED_CAST")
            (m["days"] as? ArrayList<Int>)?.let { i.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, it) }
            i.putExtra(AlarmClock.EXTRA_SKIP_UI, (m["skipUi"] as? Boolean) ?: false)
            return i
        }
        private fun map(m: Map<String, Any>): Intent {
            val lat = m["lat"] as Double; val lon = m["lon"] as Double
            val q = StringBuilder("geo:$lat,$lon?q=$lat,$lon")
            str(m, "label")?.let { q.append('(').append(Uri.encode(it)).append(')') }
            long(m, "zoom")?.let { q.append("&z=").append(it) }
            return Intent(Intent.ACTION_VIEW, Uri.parse(q.toString()))
        }
        private fun dial(m: Map<String, Any>): Intent =
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(str(m, "number"))))
        private fun sendto(m: Map<String, Any>): Intent {
            val to = str(m, "to") ?: ""
            return if (str(m, "kind") == "sms") {
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(to)))
                str(m, "body")?.let { i.putExtra("sms_body", it) }
                i
            } else {
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(to)))
                str(m, "subject")?.let { i.putExtra(Intent.EXTRA_SUBJECT, it) }
                str(m, "body")?.let { i.putExtra(Intent.EXTRA_TEXT, it) }
                i
            }
        }
        private fun contact(m: Map<String, Any>): Intent {
            val i = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
            i.putExtra(ContactsContract.Intents.Insert.NAME, str(m, "name"))
            str(m, "phone")?.let { i.putExtra(ContactsContract.Intents.Insert.PHONE, it) }
            str(m, "email")?.let { i.putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
            str(m, "company")?.let { i.putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
            str(m, "note")?.let { i.putExtra(ContactsContract.Intents.Insert.NOTES, it) }
            return i
        }

        /** The allowlist. Nothing else is reachable through this plugin. */
        val ALLOW: Map<String, Spec> = linkedMapOf(
            "calendar.insert" to Spec("calendar.insert",
                listOf(Str("title", true, 200), When("begin", true), When("end", false), Bool("allDay"), Str("description", false, 2000), Str("location", false, 200)),
                ::calendar, { listOf("calendar.insert" to calendar(mapOf("title" to "probe", "begin" to 0L))) }),
            "clock.timer" to Spec("clock.timer",
                listOf(Int_("seconds", true, 1, 86400), Str("message", false, 100), Bool("skipUi")),
                ::timer, { listOf("clock.timer" to timer(mapOf("seconds" to 60L))) }),
            "clock.alarm" to Spec("clock.alarm",
                listOf(Int_("hour", true, 0, 23), Int_("minutes", true, 0, 59), Str("message", false, 100), Days("days"), Bool("skipUi")),
                ::alarm, { listOf("clock.alarm" to alarm(mapOf("hour" to 6L, "minutes" to 0L))) }),
            "map.view" to Spec("map.view",
                listOf(Num("lat", true, -90.0, 90.0), Num("lon", true, -180.0, 180.0), Str("label", false, 100), Int_("zoom", false, 1, 21)),
                ::map, { listOf("map.view" to map(mapOf("lat" to 0.0, "lon" to 0.0))) }),
            "dial" to Spec("dial",
                listOf(Phone("number", true)),
                ::dial, { listOf("dial" to dial(mapOf("number" to "0"))) }),
            "sendto" to Spec("sendto",
                listOf(Enum_("kind", true, listOf("sms", "mail")), Str("to", true, 200), Str("subject", false, 200), Str("body", false, 4000)),
                ::sendto, { listOf("sendto.sms" to sendto(mapOf("kind" to "sms", "to" to "0")), "sendto.mail" to sendto(mapOf("kind" to "mail", "to" to "a@b.c"))) }),
            "contact.insert" to Spec("contact.insert",
                listOf(Str("name", true, 200), Phone("phone", false), Str("email", false, 200), Str("company", false, 200), Str("note", false, 1000)),
                ::contact, { listOf("contact.insert" to contact(mapOf("name" to "probe"))) }),
        )
    }
}

/** The factory an instrument lists: `override val plugins = listOf(Intent, …)`. SET_ALARM + the <queries> come with this module's manifest. */
val IntentHandoff = PluginFactory { host -> IntentPlugin(host) }
