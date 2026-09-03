package dev.smantics.scribe.model

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import dev.smantics.scribe.BuildConfig
import dev.smantics.scribe.core.model.ModelKind
import dev.smantics.scribe.core.model.ModelRegistry
import dev.smantics.scribe.core.model.ModelSpec
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Where a model is, from the app's point of view. */
sealed interface ModelState {
    data class Installed(val file: File, val bytes: Long) : ModelState
    data object Missing : ModelState
    data class Downloading(val bytesDone: Long, val bytesTotal: Long) : ModelState
    data class Failed(val reason: String) : ModelState
}

/** Progress for a running download, reported to the model manager screen. */
sealed interface DownloadProgress {
    data class Running(val bytesDone: Long, val bytesTotal: Long) : DownloadProgress {
        val fraction: Float get() = if (bytesTotal > 0) bytesDone.toFloat() / bytesTotal else 0f
    }
    data class Verifying(val bytesTotal: Long) : DownloadProgress
    data class Done(val file: File) : DownloadProgress
    data class Failed(val reason: String) : DownloadProgress
}

/**
 * Owns the model files on disk: the one bundled in the APK, and any the user has chosen
 * to download.
 *
 * Three rules shape this class.
 *
 *  1. **Scribe works before it has ever seen a network.** The everyday model ships inside
 *     the APK and is copied out on first launch. Downloading is an upgrade, never a
 *     prerequisite; the app is fully functional if it never happens.
 *  2. **Downloads are verified.** A truncated or corrupted model does not crash — it
 *     quietly transcribes badly, which is far worse. Every file is checked against the
 *     SHA-256 recorded in [ModelRegistry] before it is allowed to be used, and a partial
 *     download is resumed rather than restarted.
 *  3. **Every request is logged where the user can read it.** Not a privacy policy: a
 *     list, in Settings, of every URL this app has ever fetched. The airgap flavour has no
 *     INTERNET permission at all and the download path there refuses before it starts.
 */
class ModelStore(private val context: Context) {

    private val dir: File = File(context.filesDir, "models").apply { mkdirs() }

    val ledger = NetworkLedger(context)

    fun fileFor(spec: ModelSpec): File = File(dir, spec.fileName)

    fun state(spec: ModelSpec): ModelState {
        val file = fileFor(spec)
        return if (file.isFile && file.length() == spec.sizeBytes) {
            ModelState.Installed(file, file.length())
        } else {
            ModelState.Missing
        }
    }

    fun installed(): List<Pair<ModelSpec, File>> =
        ModelRegistry.all.mapNotNull { spec ->
            val s = state(spec)
            if (s is ModelState.Installed) spec to s.file else null
        }

    fun bytesOnDisk(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * Copy the bundled model out of the APK on first launch.
     *
     * Whisper needs a real file path — it memory-maps the weights — and an asset inside an
     * APK is not one. The copy happens once; afterwards this is a cheap existence check.
     * Returns false when the APK was built without a bundled model, which is a supported
     * configuration (the airgap flavour can be shipped with the user supplying the file).
     */
    fun stageBundledModel(): Boolean {
        val spec = ModelRegistry.EVERYDAY
        val target = fileFor(spec)
        if (target.isFile && target.length() == spec.sizeBytes) return true

        val assetPath = "models/${spec.fileName}"
        return try {
            val partial = File(dir, "${spec.fileName}.part")
            context.assets.open(assetPath).use { input ->
                partial.outputStream().use { output -> input.copyTo(output, COPY_BUFFER) }
            }
            if (partial.length() != spec.sizeBytes) {
                Log.e(TAG, "bundled model is ${partial.length()} bytes, expected ${spec.sizeBytes}")
                partial.delete()
                return false
            }
            partial.renameTo(target)
        } catch (e: IOException) {
            Log.w(TAG, "no bundled model in this build: ${e.message}")
            false
        }
    }

    fun delete(spec: ModelSpec): Boolean = fileFor(spec).delete()

    /**
     * Download [spec], resuming a partial file if one is there.
     *
     * Emits progress so the model manager can show real bytes rather than a spinner — the
     * desktop's wizard settled for an indeterminate spinner and its own roadmap lists
     * "true byte-percentage progress" as the thing it should have done.
     */
    fun download(spec: ModelSpec): Flow<DownloadProgress> = flow {
        if (!BuildConfig.NETWORK_ALLOWED) {
            emit(
                DownloadProgress.Failed(
                    "This build of Scribe has no network access at all. Copy the model file " +
                        "onto the phone and add it from Settings → Models instead.",
                ),
            )
            return@flow
        }

        val target = fileFor(spec)
        if (target.isFile && target.length() == spec.sizeBytes) {
            emit(DownloadProgress.Done(target))
            return@flow
        }

        val partial = File(dir, "${spec.fileName}.part")
        var done = if (partial.isFile) partial.length() else 0L
        if (done > spec.sizeBytes) {
            partial.delete()
            done = 0L
        }

        ledger.record(spec.url, "downloading ${spec.displayName}")

        val connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            if (done > 0) setRequestProperty("Range", "bytes=$done-")
        }

        try {
            connection.connect()
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                emit(DownloadProgress.Failed("The server answered $code. Try again later."))
                return@flow
            }
            // A server that ignored the Range header restarts the file from zero.
            if (done > 0 && code == HttpURLConnection.HTTP_OK) {
                partial.delete()
                done = 0L
            }

            emit(DownloadProgress.Running(done, spec.sizeBytes))

            connection.inputStream.use { input ->
                java.io.FileOutputStream(partial, done > 0).use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var sinceReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        sinceReport += read
                        if (sinceReport >= REPORT_EVERY) {
                            sinceReport = 0
                            emit(DownloadProgress.Running(done, spec.sizeBytes))
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // The partial file stays: the next attempt resumes rather than starting over.
            emit(DownloadProgress.Failed(friendlyNetworkError(e)))
            return@flow
        } finally {
            connection.disconnect()
        }

        if (partial.length() != spec.sizeBytes) {
            emit(
                DownloadProgress.Failed(
                    "The download stopped early (${partial.length() / 1_048_576} of " +
                        "${spec.sizeMb} MB). Tap to resume.",
                ),
            )
            return@flow
        }

        emit(DownloadProgress.Verifying(spec.sizeBytes))
        val digest = sha256(partial)
        if (!digest.equals(spec.sha256, ignoreCase = true)) {
            partial.delete()
            emit(
                DownloadProgress.Failed(
                    "The downloaded file did not match its checksum and was deleted.",
                ),
            )
            return@flow
        }

        if (!partial.renameTo(target)) {
            emit(DownloadProgress.Failed("Could not save the model — is storage full?"))
            return@flow
        }
        emit(DownloadProgress.Done(target))
    }.flowOn(Dispatchers.IO)

    /**
     * Import a model the user supplied themselves, for the airgap build or for anyone who
     * would rather not have the app fetch anything. Verified exactly like a download.
     */
    fun import(spec: ModelSpec, open: () -> java.io.InputStream): Result<File> = runCatching {
        val partial = File(dir, "${spec.fileName}.part")
        open().use { input ->
            partial.outputStream().use { output -> input.copyTo(output, COPY_BUFFER) }
        }
        require(partial.length() == spec.sizeBytes) {
            "That file is ${partial.length() / 1_048_576} MB; ${spec.displayName} is ${spec.sizeMb} MB."
        }
        require(sha256(partial).equals(spec.sha256, ignoreCase = true)) {
            "That file does not match ${spec.displayName}'s checksum."
        }
        val target = fileFor(spec)
        check(partial.renameTo(target)) { "Could not save the model — is storage full?" }
        target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun friendlyNetworkError(e: IOException): String = when {
        e.message?.contains("resolve", true) == true ->
            "No connection. The download will resume when you are back online."
        e.message?.contains("timeout", true) == true ->
            "The connection timed out. Tap to resume."
        else -> "The download was interrupted. Tap to resume."
    }

    /** Total device RAM in MB, used to decide which models to offer. */
    fun totalRamMb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return (info.totalMem / (1024 * 1024)).toInt()
    }

    fun polishModelInstalled(): Pair<ModelSpec, File>? =
        installed().firstOrNull { it.first.kind == ModelKind.POLISH }

    private companion object {
        const val TAG = "ModelStore"
        const val COPY_BUFFER = 1 shl 16
        const val REPORT_EVERY = 512L * 1024
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
