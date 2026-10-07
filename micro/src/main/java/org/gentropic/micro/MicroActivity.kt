package org.gentropic.micro

import org.gentropic.leadacid.LeadAcidActivity
import org.gentropic.leadacid.PluginFactory
import org.gentropic.leadacid.intake.Intake

/**
 * micro as an instrument. POSTURE A: micro's own file says `connect-src 'none'`
 * (Sealed); inside the shell that one directive is opened to 'self' at serve
 * time so the page can reach /native — the only origin here — while the OS
 * (no INTERNET permission) keeps "nothing leaves the device" true. Intake is
 * the "Open with micro" door; files arrive as fs tokens and read off mmap.
 */
class MicroActivity : LeadAcidActivity() {
    override val shellConnect: Boolean = true
    override val plugins: List<PluginFactory> = listOf(Intake)
}
