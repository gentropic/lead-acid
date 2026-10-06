package org.gentropic.wuffle

import org.gentropic.leadacid.LeadAcidActivity
import org.gentropic.leadacid.PluginFactory
import org.gentropic.leadacid.sensor.Sensor

/**
 * wuffle, the geological compass, as an instrument. v2 reads the fused
 * rotation vector (and the compass accuracy) through the sensor plugin; the
 * rest — keepAwake, fs/publish, the shim — is core. Still no permission:
 * the rotation vector isn't a body sensor.
 */
class WuffleActivity : LeadAcidActivity() {
    override val plugins: List<PluginFactory> = listOf(Sensor)
}
