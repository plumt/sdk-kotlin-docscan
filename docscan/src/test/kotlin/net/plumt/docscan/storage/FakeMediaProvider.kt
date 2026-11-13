package net.plumt.docscan.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File

/** MediaStore 흉내. 항목마다 임시 파일 하나. 실패 지점을 켜고 끌 수 있다. */
class FakeMediaProvider : ContentProvider() {
    class Row(val values: ContentValues, val file: File)

    companion object {
        val rows = LinkedHashMap<Long, Row>()
        val deleted = mutableListOf<Uri>()
        var insertReturnsNull = false
        var openThrows: RuntimeException? = null
        var updateThrows: RuntimeException? = null
        var renameOnInsert: String? = null
        private var nextId = 1L

        fun reset() {
            rows.clear(); deleted.clear(); insertReturnsNull = false; openThrows = null; updateThrows = null; renameOnInsert = null; nextId = 1
        }
    }

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (insertReturnsNull) return null
        val id = nextId++
        val v = ContentValues(values)
        renameOnInsert?.let { v.put(MediaStore.MediaColumns.DISPLAY_NAME, it) }
        rows[id] = Row(v, File.createTempFile("media", ".bin"))
        return Uri.withAppendedPath(uri, id.toString())
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
        updateThrows?.let { throw it }
        val row = rows[uri.lastPathSegment!!.toLong()] ?: return 0
        row.values.putAll(values)
        return 1
    }

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
        deleted += uri
        return if (rows.remove(uri.lastPathSegment!!.toLong()) != null) 1 else 0
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val c = MatrixCursor(arrayOf(MediaStore.MediaColumns.DISPLAY_NAME))
        rows[uri.lastPathSegment!!.toLong()]?.let { c.addRow(arrayOf(it.values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))) }
        return c
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        openThrows?.let { throw it }
        val row = rows[uri.lastPathSegment!!.toLong()] ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(row.file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getType(uri: Uri): String = "application/pdf"
}
