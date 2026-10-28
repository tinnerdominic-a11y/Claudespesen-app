package ch.expenses.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.YearMonth
import java.util.UUID

class ReceiptRepository(private val context: Context, private val dao: ReceiptDao) {

    private val photoDir = File(context.filesDir, "receipts").apply { mkdirs() }

    fun newPhotoFile(): File = File(photoDir, "${UUID.randomUUID()}.jpg")

    /** Copies a picked gallery image into private storage so it survives the original being deleted. */
    suspend fun importImage(uri: Uri): File = withContext(Dispatchers.IO) {
        val target = newPhotoFile()
        context.contentResolver.openInputStream(uri)!!.use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        target
    }

    fun observeMonth(month: YearMonth) =
        dao.observeRange(month.atDay(1).toEpochDay(), month.atEndOfMonth().toEpochDay())

    suspend fun receiptsForMonth(month: YearMonth) =
        dao.getRange(month.atDay(1).toEpochDay(), month.atEndOfMonth().toEpochDay())

    suspend fun get(id: Long) = dao.get(id)

    suspend fun save(receipt: Receipt): Long = dao.upsert(receipt).let { if (it == -1L) receipt.id else it }

    suspend fun delete(receipt: Receipt) = withContext(Dispatchers.IO) {
        dao.delete(receipt)
        File(receipt.imagePath).delete()
    }

    /** Removes a photo that was taken but never saved as a receipt. */
    fun discardPhoto(path: String) {
        File(path).delete()
    }
}
