package org.gentropic.leadacid.app

import org.gentropic.leadacid.LeadAcidActivity
import org.gentropic.leadacid.PluginFactory
import org.gentropic.leadacid.attest.Attest
import org.gentropic.leadacid.gnss.Gnss
import org.gentropic.leadacid.intake.Intake
import org.gentropic.leadacid.sensor.Sensor
import org.gentropic.leadacid.share.Share
import java.io.File

/**
 * The kitchen sink: every plugin module, plus the bench's built-in pattern
 * file. Hosts any artifact (`--es artifact micro.html` on debug builds).
 * This is what `app-debug.apk` is — the dev + on-device-test app.
 */
class DevActivity : LeadAcidActivity() {

    companion object {
        /** 8 MiB, byte[i] = i mod 251 — verifiable from JS at any offset. */
        const val TEST_FILE_SIZE = 8L * 1024 * 1024
    }

    override val plugins: List<PluginFactory> = listOf(Sensor, Share, Attest, Intake, Gnss)

    override fun builtinFiles(): Map<String, File> {
        val f = File(filesDir, "spike0.bin").also(::ensureTestFile)
        return mapOf("spike0.bin" to f)
    }

    /** Deterministic pattern file: byte[i] = i mod 251 (largest prime < 256). */
    private fun ensureTestFile(file: File) {
        if (file.exists() && file.length() == TEST_FILE_SIZE) return
        val chunk = ByteArray(64 * 1024)
        file.outputStream().buffered().use { out ->
            var written = 0L
            while (written < TEST_FILE_SIZE) {
                for (j in chunk.indices) chunk[j] = ((written + j) % 251).toByte()
                val n = minOf(chunk.size.toLong(), TEST_FILE_SIZE - written).toInt()
                out.write(chunk, 0, n)
                written += n
            }
        }
    }
}
