package ch.expenses.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import ch.expenses.app.ocr.Vat
import ch.expenses.app.ocr.VatLine
import java.time.LocalDate

@Entity(tableName = "receipts")
data class Receipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Absolute path of the photo in the app's private storage. */
    val imagePath: String,
    val merchant: String = "",
    /** Receipt date as epoch day; null when it wasn't read and hasn't been entered. */
    val dateEpochDay: Long? = null,
    /** Total in cents (Rappen); null when unknown. */
    val amountCents: Long? = null,
    val currency: String = "CHF",
    val category: String = "",
    val note: String = "",
    /** VAT per rate, encoded by [Vat.encode]; empty when none. */
    @ColumnInfo(defaultValue = "")
    val vat: String = "",
    /** Receipt from outside Switzerland: its VAT is foreign and kept apart in exports. */
    @ColumnInfo(defaultValue = "0")
    val abroad: Boolean = false,
    val ocrText: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val date: LocalDate? get() = dateEpochDay?.let(LocalDate::ofEpochDay)

    val vatLines: List<VatLine> get() = Vat.decode(vat)

    /** True when something the export needs is still missing. */
    val needsReview: Boolean get() = merchant.isBlank() || dateEpochDay == null || amountCents == null
}

val CATEGORIES = listOf(
    "Food & drinks", "Travel", "Fuel", "Parking", "Accommodation", "Office supplies",
    "Software", "Phone & internet", "Entertainment", "Other",
)

val CURRENCIES = listOf("CHF", "EUR", "USD", "GBP")
