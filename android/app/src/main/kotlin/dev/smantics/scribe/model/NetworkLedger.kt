package dev.smantics.scribe.model

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A visible, append-only record of every network request Scribe has ever made.
 *
 * The desktop build says "no telemetry, no analytics, no phone-home behavior" and asks to
 * be believed. On a phone, where an app can talk to anything at any time and nobody is
 * watching, a claim like that is worth very little on its own. So the standard flavour
 * keeps this list and shows it in Settings: if it contains nothing but model downloads
 * the user asked for, the claim is checkable rather than asserted.
 *
 * The airgap flavour has no INTERNET permission, so its ledger stays empty by
 * construction — which is the stronger version of the same promise.
 *
 * Nothing here is uploaded. It is a text file on the device, and the user can clear it.
 */
class NetworkLedger(context: Context) {

    private val file = File(context.filesDir, "network-ledger.tsv")
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    data class Entry(val whenText: String, val url: String, val reason: String)

    @Synchronized
    fun record(url: String, reason: String) {
        runCatching {
            // Bounded: a ledger nobody can scroll to the bottom of is not evidence.
            val existing = if (file.isFile) file.readLines() else emptyList()
            val line = "${stamp.format(Date())}\t$url\t$reason"
            val kept = (existing + line).takeLast(MAX_ENTRIES)
            file.writeText(kept.joinToString("\n"))
        }
    }

    @Synchronized
    fun entries(): List<Entry> = runCatching {
        if (!file.isFile) return emptyList()
        file.readLines().reversed().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3) null else Entry(parts[0], parts[1], parts[2])
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}
