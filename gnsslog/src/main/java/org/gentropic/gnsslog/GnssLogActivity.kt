package org.gentropic.gnsslog

import org.gentropic.leadacid.LeadAcidActivity
import org.gentropic.leadacid.PluginFactory
import org.gentropic.leadacid.gnss.Gnss
import org.gentropic.leadacid.share.Share

/**
 * gnsslog, the raw GNSS logger, as an instrument: the gnss plugin (streams +
 * the foreground-service logger — the one that brings FINE location and the
 * typed foreground-service permission) and share (a sealed log to another app).
 */
class GnssLogActivity : LeadAcidActivity() {
    override val plugins: List<PluginFactory> = listOf(Gnss, Share)
}
