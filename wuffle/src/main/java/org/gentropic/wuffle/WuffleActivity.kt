package org.gentropic.wuffle

import org.gentropic.leadacid.LeadAcidActivity
import org.gentropic.leadacid.PluginFactory
import org.gentropic.leadacid.attest.Attest
import org.gentropic.leadacid.gnss.Gnss
import org.gentropic.leadacid.intake.Intake
import org.gentropic.leadacid.sensor.Sensor

/**
 * wuffle, the geological compass, as an instrument. v2 reads the fused
 * rotation vector (and the compass accuracy) through the sensor plugin,
 * signs each published log with the attest plugin (StrongBox/TEE key), takes
 * a shared log back in (intake), and stamps each measurement with its
 * position (gnss — the ONE plugin here that brings a permission: FINE
 * location, declared by that module's manifest, asked for on first use).
 */
class WuffleActivity : LeadAcidActivity() {
    override val plugins: List<PluginFactory> = listOf(Sensor, Attest, Intake, Gnss)
}
