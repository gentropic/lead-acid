package org.gentropic.leadacid

/**
 * Shell-level controls over the bridge (SPEC §7c). Activity-touching actions
 * are handed in as lambdas so the plugin stays free of an Activity reference.
 *
 * Routes:
 *   POST /native/shell/keepawake?on=true|false  — FLAG_KEEP_SCREEN_ON
 *   GET  /native/shell/info                     — { version, present:true }
 *   GET  /native/shell/port                     — (re)post the WebMessagePort to the page
 *   GET  /native/shell/closestream?id=          — close a push stream
 */
class ShellPlugin(
    private val version: String,
    private val onKeepAwake: (Boolean) -> Unit,
    private val onCloseStream: (String) -> Unit,
    private val onRequestPort: () -> Unit,
) : Plugin {
    override val name = "shell"

    override fun handle(req: BridgeRequest): BridgeResponse = when (req.subpath) {
        "keepawake" -> {
            onKeepAwake(req.query["on"] != "false")
            BridgeResponse.json(200, "OK", """{"ok":true}""")
        }
        "closestream" -> {
            req.query["id"]?.let(onCloseStream)
            BridgeResponse.json(200, "OK", """{"ok":true}""")
        }
        "port" -> {
            onRequestPort()
            BridgeResponse.json(200, "OK", """{"ok":true}""")
        }
        "info" -> BridgeResponse.json(
            200, "OK", """{"present":true,"version":${BridgeResponse.jsonStr(version)}}""",
        )
        else -> BridgeResponse.error(404, "Not Found", "shell/${req.subpath}")
    }
}
