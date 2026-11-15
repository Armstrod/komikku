package eu.kanade.tachiyomi.data.bubble

import android.app.Application
import ca.mpreg.webgpuviewer.viewer.BubbleStore
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.util.concurrent.TimeUnit

// KMK -->
/**
 * Found speech bubbles kept on disk, by manga, chapter and page: a page's bubbles are found
 * once - while reading, or ahead of time as its chapter downloads - and open at once ever
 * after. A chapter's scans go when its download is deleted, and scans of chapters read
 * online, not downloaded, go once untouched for [ONLINE_KEEP_DAYS].
 */
object BubbleScanStore : BubbleStore {
    private val context: Application by injectLazy()

    private val root: File by lazy { File(context.filesDir, "bubble-scans") }

    /** Marks a chapter scanned while downloaded: kept for as long as its download is. */
    private const val DOWNLOADED = ".downloaded"

    /** Marks a chapter scanned through, with its page count. */
    private const val COMPLETE = ".complete"

    private const val ONLINE_KEEP_DAYS = 30L

    @Volatile
    private var trimmed = false

    fun key(mangaId: Long, chapterId: Long, pageIndex: Int) = "$mangaId/$chapterId/$pageIndex"

    private fun file(key: String) = File(root, "$key.bin")

    private fun chapterDir(mangaId: Long, chapterId: Long) = File(root, "$mangaId/$chapterId")

    override fun read(key: String): ByteArray? {
        trimOnce()
        return file(key).takeIf { it.isFile }?.readBytes()
    }

    override fun write(key: String, data: ByteArray) {
        val target = file(key)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
        // Touched, so a chapter still being read isn't trimmed.
        target.parentFile?.setLastModified(System.currentTimeMillis())
    }

    fun has(key: String): Boolean = file(key).isFile

    /** Whether [chapterId] was scanned through - every page found and kept. */
    fun isComplete(mangaId: Long, chapterId: Long): Boolean = File(chapterDir(mangaId, chapterId), COMPLETE).isFile

    /** [chapterId] scanned through while downloaded: kept for as long as the download is. */
    fun markDownloaded(mangaId: Long, chapterId: Long, pages: Int) {
        val dir = chapterDir(mangaId, chapterId)
        dir.mkdirs()
        File(dir, DOWNLOADED).writeText("")
        File(dir, COMPLETE).writeText(pages.toString())
    }

    /** The scans of [chapterIds] - their downloads deleted. */
    fun deleteChapters(mangaId: Long, chapterIds: List<Long>) {
        chapterIds.forEach { chapterDir(mangaId, it).deleteRecursively() }
        File(root, "$mangaId").takeIf { it.list()?.isEmpty() == true }?.delete()
    }

    /** Every scan of [mangaId] - its downloads deleted. */
    fun deleteManga(mangaId: Long) {
        File(root, "$mangaId").deleteRecursively()
    }

    /** Scans of chapters read online and left untouched for [ONLINE_KEEP_DAYS], once a session. */
    private fun trimOnce() {
        if (trimmed) return
        trimmed = true
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(ONLINE_KEEP_DAYS)
        Thread {
            root.listFiles()?.forEach { manga ->
                manga.listFiles()?.forEach { chapter ->
                    if (!File(chapter, DOWNLOADED).exists() && chapter.lastModified() < cutoff) {
                        chapter.deleteRecursively()
                    }
                }
                if (manga.list()?.isEmpty() == true) manga.delete()
            }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }
}
// KMK <--
