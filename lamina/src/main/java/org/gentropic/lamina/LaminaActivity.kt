package org.gentropic.lamina

import org.gentropic.leadacid.LeadAcidActivity
import org.gentropic.leadacid.PluginFactory
import org.gentropic.leadacid.intake.Intake
import org.gentropic.leadacid.share.Share
import org.gentropic.leadacid.share.Share

/**
 * lamina as an instrument. POSTURE A: lamina's own file says `connect-src 'none'`
 * (Sealed); inside the shell that one directive is opened to 'self' at serve
 * time so the page can reach /native — the only origin here — while the OS
 * (no INTERNET permission) keeps "nothing leaves the device" true. Intake is
 * the "Open with lamina" door (files arrive as fs tokens and index off mmap,
 * never resident); Share is the way out for its exports and lenses.
 */
class LaminaActivity : LeadAcidActivity() {
    override val shellConnect: Boolean = true
    override val plugins: List<PluginFactory> = listOf(Intake, Share)
}
