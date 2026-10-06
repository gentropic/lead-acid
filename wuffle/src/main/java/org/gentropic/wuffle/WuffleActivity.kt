package org.gentropic.wuffle

import org.gentropic.leadacid.LeadAcidActivity
import org.gentropic.leadacid.PluginFactory

/**
 * wuffle, the geological compass, as an instrument. It uses only what core
 * always provides — keepAwake, fs/publish, the shim — so the capability list
 * is empty and the merged manifest carries no permission. wuffle v2 (the
 * rotation-vector + magnetometer calibration path) adds `Sensor` here and
 * `:plugin-sensor` in build.gradle.kts.
 */
class WuffleActivity : LeadAcidActivity() {
    override val plugins: List<PluginFactory> = emptyList()
}
