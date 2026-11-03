package com.arenascanner.app

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore

/** Keeps an up-to-date copy of the collection in Downloads/ArenaScanner after every scan session. */
object Backup {
    const val NAME = "arena_collection_autobackup.csv"
    private val DIR = Environment.DIRECTORY_DOWNLOADS + "/ArenaScanner/"

    fun save(ctx: Context): Boolean = runCatching {
        val r = ctx.contentResolver
        // Replace the previous automatic backup (files this app created can be deleted without asking).
        r.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(NAME, DIR))
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DIR)
        }) ?: return false
        r.openOutputStream(uri)?.use { it.write(CollectionStore.exportCsv(ctx).toByteArray()) }
        true
    }.getOrDefault(false)
}
