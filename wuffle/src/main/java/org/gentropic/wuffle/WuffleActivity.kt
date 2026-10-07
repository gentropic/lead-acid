package org.gentropic.wuffle

import org.gentropic.leadacid.LeadAcidActivity
import org.gentropic.leadacid.PluginFactory
import org.gentropic.leadacid.attest.Attest
import org.gentropic.leadacid.intake.Intake
import org.gentropic.leadacid.sensor.Sensor

/**
 * wuffle, the geological compass, as an instrument. v2 reads the fused
 * rotation vector (and the compass accuracy) through the sensor plugin and
 * signs each published log with the attest plugin (StrongBox/TEE key); the
 * rest — keepAwake, fs/publish, the shim — is core. Still no permission:
 * neither the rotation vector nor the keystore needs one.
 */
class WuffleActivity : LeadAcidActivity() {
    override val plugins: List<PluginFactory> = listOf(Sensor, Attest, Intake)
}
