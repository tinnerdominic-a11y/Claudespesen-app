package ch.expenses.app.ocr

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** What the scanner could read off a receipt. Any field may be null when it wasn't found. */
data class ParsedReceipt(
    val merchant: String?,
    val date: LocalDate?,
    val totalCents: Long?,
    val currency: String?,
    val vat: List<VatLine> = emptyList(),
    /** Probably from outside Switzerland; see [Vat.isLikelyForeign]. */
    val abroad: Boolean = false,
)

/**
 * Pulls merchant, date, total and currency out of OCR text. Tuned for Swiss / European
 * receipts (German, French, Italian and English keywords) but falls back to generic rules.
 */
object ReceiptParser {

    private val totalKeywords = listOf(
        "total", "totale", "totaal", "summe", "gesamt", "gesamtbetrag", "endbetrag",
        "zu bezahlen", "zu zahlen", "betrag", "montant", "à payer", "a payer", "da pagare",
        "importo", "amount due", "grand total", "bar", "karte", "card", "twint",
    )
    // Lines that contain amounts but are never the total we want.
    private val excludeKeywords = listOf(
        "mwst", "mwst.", "tva", "iva", "vat", "steuer", "zwischensumme", "subtotal", "sous-total",
        "rückgeld", "rueckgeld", "retour", "change", "rabatt", "discount", "netto",
    )
    private val inclusiveRegex = Regex("""\b(inkl|incl|including|inclus|compris|ttc)\b""")
    private val merchantSkip = listOf(
        "quittung", "beleg", "kassenbon", "receipt", "rechnung", "facture", "ricevuta", "kasse",
        "tel", "tel.", "www", "http", "mwst", "uid", "che-", "datum", "date", "willkommen",
        "welcome", "bienvenue",
    )

    private val currencyPatterns = listOf(
        "CHF" to Regex("""\bCHF\b|\bSFr\.?|\bFr\.\s?\d""", RegexOption.IGNORE_CASE),
        "EUR" to Regex("""\bEUR\b|€"""),
        "USD" to Regex("""\bUSD\b|US\$|\$\s?\d"""),
        "GBP" to Regex("""\bGBP\b|£"""),
    )

    // 12.50  1'234.50  1,234.50  12,50  1.234,50  12.-  12.--
    private val amountRegex =
        Regex(
        """(?<![\d.,'’])(\d{1,3}(?:['’]\d{3})+|\d{1,3}(?:\.\d{3})+(?=,)|\d{1,3}(?:,\d{3})+(?=\.)|\d+)""" +
            """([.,])(\d{2}|-{1,2})(?![\d])""",
    )

    private val dateFormats = listOf(
        Regex("""\b(\d{1,2})[./-](\d{1,2})[./-](\d{4})\b""") to "dmy4",
        Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""") to "ymd",
        Regex("""\b(\d{1,2})[./-](\d{1,2})[./-](\d{2})\b""") to "dmy2",
    )

    fun parse(text: String, today: LocalDate = LocalDate.now()): ParsedReceipt {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val total = findTotal(lines)
        val currency = findCurrency(text)
        val vat = Vat.parse(lines, total)
        return ParsedReceipt(
            merchant = findMerchant(lines),
            date = findDate(lines, today),
            totalCents = total,
            currency = currency,
            vat = vat,
            abroad = Vat.isLikelyForeign(currency, vat, text),
        )
    }

    fun findCurrency(text: String): String? =
        currencyPatterns.map { (code, re) -> code to re.findAll(text).count() }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first

    fun findMerchant(lines: List<String>): String? {
        for (line in lines.take(8)) {
            val lower = line.lowercase()
            val letters = line.count { it.isLetter() }
            if (letters < 3) continue
            if (letters < line.length / 2) continue // mostly digits: address, phone, date
            if (merchantSkip.any { lower.startsWith(it) || lower.contains(" $it") }) continue
            if (amountRegex.containsMatchIn(line)) continue
            return line.trim(' ', '*', '-', '=', '.').take(60)
        }
        return null
    }

    fun findDate(lines: List<String>, today: LocalDate): LocalDate? {
        val candidates = mutableListOf<LocalDate>()
        for (line in lines) {
            for ((re, kind) in dateFormats) {
                for (m in re.findAll(line)) {
                    val g = m.groupValues
                    val date = when (kind) {
                        "ymd" -> safeDate(g[1].toInt(), g[2].toInt(), g[3].toInt())
                        "dmy4" -> safeDate(g[3].toInt(), g[2].toInt(), g[1].toInt())
                        else -> safeDate(2000 + g[3].toInt(), g[2].toInt(), g[1].toInt())
                    }
                    if (date != null) candidates += date
                }
            }
        }
        // Ignore dates in the future (often "best before" or warranty dates) and very old ones.
        return candidates.firstOrNull { !it.isAfter(today) && it.isAfter(today.minusYears(5)) }
            ?: candidates.firstOrNull()
    }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? = try {
        LocalDate.of(y, m, d)
    } catch (e: Exception) {
        null
    }

    fun findTotal(lines: List<String>): Long? {
        // 1) A line with a total keyword (or the line right after it, when the amount is on its own line).
        for ((i, line) in lines.withIndex()) {
            val lower = line.lowercase()
            val inclusive = inclusiveRegex.containsMatchIn(lower) // "Total inkl. MWST 12.50"
            if (!inclusive && excludeKeywords.any { lower.contains(it) }) continue
            if (totalKeywords.none { Regex("""(^|[^a-zäöüéà])${Regex.escape(it)}([^a-zäöüéà]|$)""").containsMatchIn(lower) }) continue
            val amounts = amountsIn(line)
            if (amounts.isNotEmpty()) return amounts.last()
            val next = lines.getOrNull(i + 1)?.let { amountsIn(it) }.orEmpty()
            if (next.isNotEmpty()) return next.last()
        }
        // 2) Otherwise the largest amount on the receipt.
        return lines.filter { l -> excludeKeywords.none { l.lowercase().contains(it) } }
            .flatMap { amountsIn(it) }
            .filter { it < 10_000_000 }
            .maxOrNull()
    }

    fun amountsIn(line: String): List<Long> {
        // Skip dates, times and percentages so 12.05.2024 or 7.70% aren't read as amounts.
        val cleaned = line
            .replace(Regex("""\b\d{1,2}[./-]\d{1,2}[./-]\d{2,4}\b"""), " ")
            .replace(Regex("""\b\d{1,2}:\d{2}(:\d{2})?\b"""), " ")
            .replace(Regex("""\d{1,2}(?:[.,]\d{1,2})?\s?%"""), " ") // percentages like 7.70%
        return amountRegex.findAll(cleaned).mapNotNull { m ->
            val whole = m.groupValues[1].replace(Regex("""['’.,]"""), "").toLongOrNull()
                ?: return@mapNotNull null
            val frac = m.groupValues[3].let { if (it.startsWith("-")) 0L else it.toLong() }
            whole * 100 + frac
        }.toList()
    }

    /** Parses what a user types in the amount field: "12.50", "12,50", "1'234.5". */
    fun parseUserAmount(input: String): Long? {
        val s = input.trim().replace(Regex("""['’ ]"""), "")
        if (s.isEmpty()) return null
        val normalized = when {
            s.contains(',') && s.contains('.') ->
                if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replace(',', '.')
                else s.replace(",", "")
            else -> s.replace(',', '.')
        }
        val value = normalized.toBigDecimalOrNull() ?: return null
        return value.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
    }

    fun formatAmount(cents: Long): String {
        val abs = kotlin.math.abs(cents)
        val whole = (abs / 100).toString().reversed().chunked(3).joinToString("'").reversed()
        return (if (cents < 0) "-" else "") + whole + "." + (abs % 100).toString().padStart(2, '0')
    }

    val displayDate: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun parseUserDate(input: String): LocalDate? = try {
        LocalDate.parse(input.trim(), displayDate)
    } catch (e: DateTimeParseException) {
        null
    }
}
