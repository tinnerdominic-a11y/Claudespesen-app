package ch.expenses.app.ocr

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * One VAT entry on a receipt. [rateBp] is the rate in basis points (810 = 8.1 %), null when the
 * receipt only states a VAT amount without its rate.
 */
data class VatLine(val rateBp: Int?, val amountCents: Long)

object Vat {

    /** Current Swiss rates first, 0 % (exempt / import), then common EU ones, offered in the form. */
    val COMMON_RATES_BP = listOf(810, 260, 380, 0, 2000, 1900, 700, 2200, 1000, 550)

    /** Swiss rates, current (since 2024) and previous. Any other rate means a foreign receipt. */
    private val SWISS_RATES_BP = setOf(810, 260, 380, 770, 250, 370)
    private val foreignMarkers = listOf(
        "ust-idnr", "ust-id", "ust.-id", "steuernummer", "steuer-nr", "partita iva", "p.iva", "p. iva",
        "siret", "tva intracom", "deutschland", "österreich", "italia", "france", "nederland",
    )
    private val swissUid = Regex("""che[- ]?\d{3}""")

    /**
     * Guesses whether a receipt is from outside Switzerland (its VAT can't be claimed back in the
     * Swiss VAT return): a non-CHF currency, a non-Swiss VAT rate, or foreign company/tax ids.
     */
    fun isLikelyForeign(currency: String?, vat: List<VatLine>, text: String): Boolean {
        if (currency != null && currency != "CHF") return true
        if (vat.any { it.rateBp != null && it.rateBp !in SWISS_RATES_BP }) return true
        val lower = text.lowercase()
        if (swissUid.containsMatchIn(lower)) return false
        return foreignMarkers.any { containsWord(lower, it) }
    }

    private val keywords = listOf(
        "mwst", "mws", "mehrwertsteuer", "ust", "tva", "iva", "vat", "btw", "moms",
    )
    private val rateRegex = Regex("""(?<![\d.,])(\d{1,2}(?:[.,]\d{1,2})?)\s?%""")
    private val ignoreLines = listOf("rabatt", "discount", "aktion", "remise", "sconto", "%-")

    fun formatRate(rateBp: Int): String {
        val whole = rateBp / 100
        val frac = rateBp % 100
        return when {
            frac == 0 -> "$whole%"
            frac % 10 == 0 -> "$whole.${frac / 10}%"
            else -> "$whole.${frac.toString().padStart(2, '0')}%"
        }
    }

    fun parseRate(input: String): Int? {
        val v = input.trim().removeSuffix("%").trim().replace(',', '.').toBigDecimalOrNull() ?: return null
        if (v.signum() < 0 || v > 50.toBigDecimal()) return null
        return v.movePointRight(2).toInt()
    }

    /** VAT contained in a gross amount: gross × rate / (100 % + rate), rounded to the cent. */
    fun fromGross(grossCents: Long, rateBp: Int): Long =
        (grossCents * rateBp.toDouble() / (10_000 + rateBp)).roundToLong()

    /** Stored as "810:92,260:20" ("-" for an unknown rate) so it fits one database column. */
    fun encode(lines: List<VatLine>): String =
        lines.joinToString(",") { "${it.rateBp ?: "-"}:${it.amountCents}" }

    fun decode(s: String): List<VatLine> =
        s.split(',').mapNotNull { part ->
            val (rate, amount) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            VatLine(rate.toIntOrNull(), amount.toLongOrNull() ?: return@mapNotNull null)
        }

    /**
     * Finds VAT on the receipt. Handles single lines ("MWST 8.1% 0.92", "inkl. 8.1% MWST CHF 0.92")
     * and the Swiss rate tables ("A 8.1%  12.50  0.94"), where the VAT is the amount that matches
     * the rate applied to one of the other amounts on that line. A rate stated without an amount
     * ("inkl. MWST 8.1%") is turned into an amount computed from [totalCents].
     */
    fun parse(lines: List<String>, totalCents: Long?): List<VatLine> {
        val lower = lines.map { it.lowercase() }
        if (lower.none { l -> keywords.any { containsWord(l, it) } }) return emptyList()

        val found = linkedMapOf<Int?, Long>()
        val ratesWithoutAmount = mutableListOf<Int>()

        for ((i, line) in lines.withIndex()) {
            val l = lower[i]
            if (ignoreLines.any { l.contains(it) }) continue
            val rateMatch = rateRegex.find(line)
            val hasKeyword = keywords.any { containsWord(l, it) }
            if (rateMatch == null && !hasKeyword) continue

            val rate = rateMatch?.let { parseRate(it.groupValues[1]) }?.takeIf { it in 1..3000 }
            val amounts = ReceiptParser.amountsIn(line.replace(rateRegex, " "))
            if (rate == null) {
                // "MWST total 1.12": a VAT amount without a rate; only used if no rated lines exist.
                if (hasKeyword && amounts.size == 1 && !found.containsKey(null)) found[null] = amounts[0]
                continue
            }
            if (found.containsKey(rate)) continue
            val vat = pickVat(amounts, rate)
            if (vat != null) found[rate] = vat
            else if (hasKeyword) ratesWithoutAmount += rate
        }

        val rated = found.filterKeys { it != null }
        if (rated.isNotEmpty()) return rated.map { (r, a) -> VatLine(r, a) }
        found[null]?.let { return listOf(VatLine(null, it)) }
        val onlyRate = ratesWithoutAmount.distinct().singleOrNull()
        if (onlyRate != null && totalCents != null) return listOf(VatLine(onlyRate, fromGross(totalCents, onlyRate)))
        return emptyList()
    }

    private fun pickVat(amounts: List<Long>, rateBp: Int): Long? {
        if (amounts.isEmpty()) return null
        if (amounts.size == 1) return amounts[0]
        // Prefer the amount that equals rate × (net or gross) of another amount on the line.
        for (vat in amounts.sorted()) {
            val matches = amounts.any { other ->
                other != vat && (
                    abs(fromGross(other, rateBp) - vat) <= 2 ||
                        abs((other * rateBp / 10_000.0).roundToLong() - vat) <= 2
                    )
            }
            if (matches) return vat
        }
        return amounts.min()
    }

    private fun containsWord(text: String, word: String) =
        Regex("""(^|[^a-zäöü])${Regex.escape(word)}([^a-zäöü]|$)""").containsMatchIn(text)
}
