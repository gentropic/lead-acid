package org.gentropic.leadacid.template

import org.gentropic.leadacid.LeadAcidActivity
import org.gentropic.leadacid.PluginFactory
import org.gentropic.leadacid.share.Share

/**
 * An instrument is a LeadAcidActivity subclass plus a list. That's the whole
 * wiring: `fs` + `shell` are always there; each factory you list here must
 * come from a module in template/build.gradle.kts — the dependency is what
 * carries its permissions into the manifest (SPEC §5).
 */
class InstrumentActivity : LeadAcidActivity() {
    override val plugins: List<PluginFactory> = listOf(Share)
}
