package eu.kanade.tachiyomi.data.bubble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ca.mpreg.webgpuviewer.viewer.BubbleScan
import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.ByteBuffer
import kotlin.math.max

// KMK -->
/**
 * Finds the speech bubbles on a manga's downloaded chapters ahead of time, when the device has
 * room to spare - battery and storage not low - and keeps them in [BubbleScanStore], so the
 * reader opens each page's bubbles at once. Only while bubble stepping or docking is on.
 */
class BubbleScanJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Injekt.get<ReaderPreferences>()
        if (!wanted(prefs)) return Result.success()
        val mangaId = inputData.getLong(KEY_MANGA, -1L)
        val only = inputData.getLongArray(KEY_CHAPTERS)?.toSet()
        val manga = Injekt.get<GetManga>().await(mangaId) ?: return Result.success()
        val source = Injekt.get<SourceManager>().getOrStub(manga.source)
        val downloads = Injekt.get<DownloadManager>()
        val provider = Injekt.get<DownloadProvider>()
        BubbleScan.store = BubbleScanStore

        val chapters = Injekt.get<GetChaptersByMangaId>().await(mangaId)
            .filter { only == null || it.id in only }
            .filter { !BubbleScanStore.isComplete(mangaId, it.id) }
            .filter { downloads.isChapterDownloaded(it.name, it.scanlator, it.url, manga.ogTitle, manga.source) }
            .sortedBy { it.chapterNumber }
        for (chapter in chapters) {
            if (isStopped || !wanted(prefs)) break
            val loader = DownloadPageLoader(ReaderChapter(chapter.toDbChapter()), manga, source, downloads, provider)
            try {
                val pages = loader.getPages()
                var done = true
                for (page in pages) {
                    if (isStopped) {
                        done = false
                        break
                    }
                    val key = BubbleScanStore.key(mangaId, chapter.id, page.index)
                    if (BubbleScanStore.has(key)) continue
                    loader.loadPage(page)
                    val open = page.stream ?: continue
                    runCatching {
                        withContext(Dispatchers.Default) { scanPage(open, key) }
                    }.onFailure { logcat(LogPriority.WARN, it) { "Bubble scan failed: $key" } }
                }
                if (done) BubbleScanStore.markDownloaded(mangaId, chapter.id, pages.size)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Bubble scan failed for chapter ${chapter.id}" }
            } finally {
                loader.recycle()
            }
        }
        return Result.success()
    }

    companion object {
        private const val KEY_MANGA = "manga"
        private const val KEY_CHAPTERS = "chapters"

        /** The finder's map is this large on its long side - see InkMap. */
        private const val MAP_SIDE = 1280

        /**
         * Decodes a page as the reader would see it - no larger than needed - finds its bubbles
         * and keeps them under [key]. Slow: off the main thread. False where it couldn't be
         * decoded.
         */
        fun scanPage(open: () -> java.io.InputStream, key: String, rightToLeft: Boolean = true): Boolean {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open().use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            // Halved while it stays larger than the finder's map - it only needs that much.
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAP_SIDE) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = open().use { BitmapFactory.decodeStream(it, null, options) } ?: return false
            try {
                val pixels = ByteBuffer.allocateDirect(bitmap.byteCount)
                bitmap.copyPixelsToBuffer(pixels)
                pixels.rewind()
                BubbleScan.scan(pixels, bitmap.width, bitmap.height, key, rightToLeft)
            } finally {
                bitmap.recycle()
            }
            return true
        }

        private fun wanted(prefs: ReaderPreferences): Boolean =
            prefs.bubbleZoom().get() && (prefs.bubbleGuided().get() || prefs.bubbleDock().get())

        /**
         * Finds the bubbles on [mangaId]'s downloaded chapters - [chapterIds] only, where given -
         * when the device has room to spare. Queued after any scan of the same manga.
         */
        fun enqueue(context: Context, mangaId: Long, chapterIds: List<Long>? = null) {
            if (!wanted(Injekt.get())) return
            val request = OneTimeWorkRequestBuilder<BubbleScanJob>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .setInputData(
                    if (chapterIds == null) {
                        workDataOf(KEY_MANGA to mangaId)
                    } else {
                        workDataOf(KEY_MANGA to mangaId, KEY_CHAPTERS to chapterIds.toLongArray())
                    },
                )
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("$TAG-$mangaId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        private const val TAG = "BubbleScan"
    }
}
// KMK <--
