package eu.kanade.tachiyomi.data.bubble

import android.content.Context
import android.util.Base64
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// KMK -->
/**
 * Uploads bubble problem reports - one-page debug logs written when a problem is reported in
 * the reader - to a GitHub repository, one file each under `reports/<date>/`, with the token
 * set in the reader settings. Each is deleted from the phone once uploaded; with no token, or
 * no connection, they wait.
 */
class BubbleReportJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = Injekt.get<ReaderPreferences>()
        val repo = prefs.bubbleReportRepo().get().trim().trim('/')
        val token = prefs.bubbleReportToken().get().trim()
        if (repo.isEmpty() || token.isEmpty()) return@withContext Result.success()
        val files = queueDir(applicationContext).listFiles { f -> f.extension == "zip" }.orEmpty().sortedBy { it.name }
        val client = Injekt.get<NetworkHelper>().client
        var retry = false
        for (file in files) {
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(file.lastModified()))
            val body = JSONObject()
                .put("message", "Bubble report ${file.nameWithoutExtension}")
                .put("content", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
                .toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://api.github.com/repos/$repo/contents/reports/$day/${file.name}")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .put(body)
                .build()
            val code = runCatching { client.newCall(request).execute().use { it.code } }
                .onFailure { logcat(LogPriority.WARN, it) { "Bubble report upload failed" } }
                .getOrDefault(-1)
            when (code) {
                // Uploaded - or already there, from an attempt whose answer was lost.
                200, 201, 422 -> file.delete()
                -1, 408, 429, in 500..599 -> retry = true
                else -> logcat(LogPriority.WARN) { "Bubble report upload refused: HTTP $code" }
            }
        }
        if (retry) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "BubbleReport"

        /** Where reports wait on the phone until uploaded. */
        fun queueDir(context: Context): File = File(context.filesDir, "bubble-reports").apply { mkdirs() }

        /** Queues the waiting reports to upload when online. False where there's no token to upload with. */
        fun enqueue(context: Context): Boolean {
            val prefs = Injekt.get<ReaderPreferences>()
            if (prefs.bubbleReportToken().get().isBlank()) return false
            val request = OneTimeWorkRequestBuilder<BubbleReportJob>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            return true
        }
    }
}
// KMK <--
