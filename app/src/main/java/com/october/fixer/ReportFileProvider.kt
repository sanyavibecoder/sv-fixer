package com.october.fixer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/** Read-only access to individual diagnostic exports made by this app. */
class ReportFileProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri): String? = if (reportFile(uri) != null) MIME_TYPE else null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = reportFile(uri)?.takeIf(File::isFile) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> FILE_NAME
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException()
        val file = reportFile(uri) ?: throw FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    private fun reportFile(uri: Uri): File? {
        val segments = uri.pathSegments
        if (segments.size != 2 || !REPORT_ID.matches(segments[0]) || segments[1] != FILE_NAME) return null
        val ctx = context ?: return null
        return File(directory(ctx), segments[0] + ".txt")
    }

    companion object {
        const val FILE_NAME = "sv-fixer-report.txt"
        const val MIME_TYPE = "text/plain"
        private const val CACHE_RETENTION_MS = 24 * 60 * 60 * 1000L
        private val REPORT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        fun create(context: Context, report: String): Uri {
            val dir = directory(context)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create report cache")
            val staleBefore = System.currentTimeMillis() - CACHE_RETENTION_MS
            dir.listFiles { file -> file.extension == "txt" && REPORT_ID.matches(file.nameWithoutExtension) }
                ?.filter { it.lastModified() < staleBefore }?.forEach { it.delete() }
            val id = UUID.randomUUID().toString()
            File(dir, "$id.txt").writeText(report, Charsets.UTF_8)
            return Uri.Builder()
                .scheme("content")
                .authority(context.packageName + ".diagnostic-reports")
                .appendPath(id)
                .appendPath(FILE_NAME)
                .build()
        }

        private fun directory(context: Context) = File(context.cacheDir, "diagnostic-reports")
    }
}
