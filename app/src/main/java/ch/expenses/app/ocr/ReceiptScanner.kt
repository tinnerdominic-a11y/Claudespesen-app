package ch.expenses.app.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File

/** Runs on-device OCR (ML Kit, Latin script) on a receipt photo. */
class ReceiptScanner(private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Returns the recognised text in reading order, or "" if nothing could be read. */
    suspend fun readText(photo: File): String = try {
        val image = InputImage.fromFilePath(context, Uri.fromFile(photo))
        val result = recognizer.process(image).await()
        // ML Kit returns blocks; split into lines, sorted top-to-bottom so totals keep their row.
        result.textBlocks
            .flatMap { it.lines }
            .sortedWith(compareBy({ it.boundingBox?.centerY() ?: 0 }, { it.boundingBox?.left ?: 0 }))
            .let(::mergeRows)
    } catch (e: Exception) {
        ""
    }

    /**
     * Receipts print label and amount in separate columns, which ML Kit often reports as two
     * lines. Lines whose vertical centres are close are joined so "TOTAL      12.50" stays together.
     */
    private fun mergeRows(lines: List<com.google.mlkit.vision.text.Text.Line>): String {
        val rows = mutableListOf<MutableList<com.google.mlkit.vision.text.Text.Line>>()
        for (line in lines) {
            val box = line.boundingBox
            val row = rows.lastOrNull()
            val prev = row?.lastOrNull()?.boundingBox
            if (box != null && prev != null &&
                kotlin.math.abs(box.centerY() - prev.centerY()) < prev.height() / 2
            ) {
                row.add(line)
            } else {
                rows.add(mutableListOf(line))
            }
        }
        return rows.joinToString("\n") { row ->
            row.sortedBy { it.boundingBox?.left ?: 0 }.joinToString("   ") { it.text }
        }
    }
}
