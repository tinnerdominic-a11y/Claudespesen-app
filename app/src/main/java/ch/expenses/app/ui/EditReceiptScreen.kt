package ch.expenses.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ch.expenses.app.data.CATEGORIES
import ch.expenses.app.data.CURRENCIES
import ch.expenses.app.data.Receipt
import ch.expenses.app.data.ReceiptRepository
import ch.expenses.app.ocr.ReceiptParser
import ch.expenses.app.ocr.ReceiptScanner
import ch.expenses.app.ocr.Vat
import ch.expenses.app.ocr.VatLine
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

/** Form state kept as strings so half-typed input survives rotation. */
private class Form {
    var merchant by mutableStateOf("")
    var date by mutableStateOf("")
    var amount by mutableStateOf("")
    var currency by mutableStateOf("CHF")
    var category by mutableStateOf("")
    var note by mutableStateOf("")
    var abroad by mutableStateOf(false)
    val vat = mutableStateListOf<VatRow>()

    fun setVat(lines: List<VatLine>) {
        vat.clear()
        lines.forEach { l ->
            vat += VatRow(l.rateBp?.let { Vat.formatRate(it).removeSuffix("%") } ?: "", plainAmount(l.amountCents))
        }
    }

    companion object {
        val Saver = listSaver<Form, String>(
            save = {
                listOf(it.merchant, it.date, it.amount, it.currency, it.category, it.note, it.abroad.toString()) +
                    it.vat.map { r -> "${r.rate}\t${r.amount}" }
            },
            restore = { v ->
                Form().apply {
                    merchant = v[0]; date = v[1]; amount = v[2]; currency = v[3]; category = v[4]; note = v[5]
                    abroad = v[6].toBoolean()
                    v.drop(7).forEach { row -> row.split('\t').let { vat += VatRow(it[0], it.getOrElse(1) { "" }) } }
                }
            },
        )
    }
}

/** A VAT line as typed: rate in percent ("8.1") and amount ("0.92"), both may be blank. */
private data class VatRow(val rate: String, val amount: String)

private fun plainAmount(cents: Long) = ReceiptParser.formatAmount(cents).replace("'", "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditReceiptScreen(
    repository: ReceiptRepository,
    receiptId: Long?,
    newPhotoPath: String?,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val form = rememberSaveable(saver = Form.Saver) { Form() }
    // Set once the form has been filled, so rotation doesn't re-run OCR over the user's edits.
    var loaded by rememberSaveable { mutableStateOf(false) }
    var original by remember { mutableStateOf<Receipt?>(null) }
    var imagePath by rememberSaveable { mutableStateOf(newPhotoPath ?: "") }
    var ocrText by rememberSaveable { mutableStateOf("") }
    var scanning by rememberSaveable { mutableStateOf(newPhotoPath != null) }
    var unread by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var photoExpanded by remember { mutableStateOf(false) }
    var triedSave by remember { mutableStateOf(false) }
    val isNew = receiptId == null

    LaunchedEffect(receiptId, newPhotoPath) {
        if (loaded) {
            if (receiptId != null) original = repository.get(receiptId)
            return@LaunchedEffect
        }
        if (receiptId != null) {
            repository.get(receiptId)?.let { r ->
                original = r
                imagePath = r.imagePath
                ocrText = r.ocrText
                form.merchant = r.merchant
                form.date = r.date?.format(ReceiptParser.displayDate) ?: ""
                form.amount = r.amountCents?.let { ReceiptParser.formatAmount(it).replace("'", "") } ?: ""
                form.currency = r.currency
                form.category = r.category
                form.note = r.note
                form.setVat(r.vatLines)
                form.abroad = r.abroad
            }
        } else if (newPhotoPath != null) {
            val text = ReceiptScanner(context).readText(File(newPhotoPath))
            val parsed = ReceiptParser.parse(text)
            ocrText = text
            form.merchant = parsed.merchant ?: ""
            form.date = (parsed.date ?: LocalDate.now()).format(ReceiptParser.displayDate)
            form.amount = parsed.totalCents?.let { ReceiptParser.formatAmount(it).replace("'", "") } ?: ""
            form.currency = parsed.currency ?: "CHF"
            form.setVat(parsed.vat)
            form.abroad = parsed.abroad
            unread = buildList {
                if (parsed.merchant == null) add("company")
                if (parsed.date == null) add("date (set to today)")
                if (parsed.totalCents == null) add("amount")
            }
            scanning = false
        }
        loaded = true
    }

    fun discardAndLeave() {
        if (isNew) repository.discardPhoto(imagePath)
        onDone()
    }
    BackHandler { discardAndLeave() }

    val amountCents = ReceiptParser.parseUserAmount(form.amount)
    val date = ReceiptParser.parseUserDate(form.date)
    val amountError = form.amount.isNotBlank() && amountCents == null
    val dateError = form.date.isNotBlank() && date == null
    val vatRates = form.vat.map { if (it.rate.isBlank()) null else Vat.parseRate(it.rate) }
    val vatAmounts = form.vat.map { ReceiptParser.parseUserAmount(it.amount) }
    val vatRateErrors = form.vat.mapIndexed { i, r -> r.rate.isNotBlank() && vatRates[i] == null }
    val vatAmountErrors = form.vat.mapIndexed { i, r -> r.amount.isNotBlank() && vatAmounts[i] == null }
    // With a single rate and no amount typed, the VAT is worked out from the total.
    fun autoVat(i: Int): Long? {
        val rate = vatRates[i] ?: return null
        if (rate == 0) return 0
        if (form.vat.size != 1 || amountCents == null) return null
        return Vat.fromGross(amountCents, rate)
    }

    fun save() {
        triedSave = true
        if (amountError || dateError || vatRateErrors.any { it } || vatAmountErrors.any { it }) return
        val vatLines = form.vat.indices.mapNotNull { i ->
            (vatAmounts[i] ?: autoVat(i))?.let { VatLine(vatRates[i], it) }
        }
        val base = original ?: Receipt(imagePath = imagePath)
        scope.launch {
            repository.save(
                base.copy(
                    merchant = form.merchant.trim(),
                    dateEpochDay = date?.toEpochDay(),
                    amountCents = amountCents,
                    currency = form.currency,
                    category = form.category,
                    note = form.note.trim(),
                    vat = Vat.encode(vatLines),
                    abroad = form.abroad,
                    ocrText = ocrText,
                )
            )
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New receipt" else "Edit receipt") },
                navigationIcon = {
                    IconButton(onClick = ::discardAndLeave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (isNew) "Discard" else "Back")
                    }
                },
                actions = {
                    if (!isNew) IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete receipt")
                    }
                    TextButton(onClick = ::save, enabled = !scanning) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (imagePath.isNotEmpty()) {
                AsyncImage(
                    model = File(imagePath),
                    contentDescription = "Receipt photo, tap to enlarge",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().animateContentSize()
                        .heightIn(max = if (photoExpanded) 2000.dp else 240.dp)
                        .clickable { photoExpanded = !photoExpanded },
                )
            }

            if (scanning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp))
                    Text("Reading receipt…")
                }
                return@Column
            }

            if (unread.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        "Couldn't read: ${unread.joinToString(", ")}. Please check and fill in manually.",
                        Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            } else if (isNew) {
                Text(
                    "Filled in from the photo. Check the values before saving.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = form.merchant,
                onValueChange = { form.merchant = it },
                label = { Text("Company / merchant") },
                singleLine = true,
                isError = triedSave && form.merchant.isBlank(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.amount,
                    onValueChange = { form.amount = it },
                    label = { Text("Amount") },
                    singleLine = true,
                    isError = amountError || (triedSave && form.amount.isBlank()),
                    supportingText = { if (amountError) Text("e.g. 12.50") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
                Picker("Currency", form.currency, CURRENCIES, Modifier.weight(0.7f)) { form.currency = it }
            }

            Row(
                Modifier.fillMaxWidth().clickable { form.abroad = !form.abroad },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Receipt from abroad", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Foreign VAT is listed separately in exports",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = form.abroad, onCheckedChange = { form.abroad = it })
            }

            VatSection(form, vatRateErrors, vatAmountErrors, ::autoVat)

            OutlinedTextField(
                value = form.date,
                onValueChange = { form.date = it },
                label = { Text("Date (dd.mm.yyyy)") },
                singleLine = true,
                isError = dateError,
                trailingIcon = {
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Default.DateRange, contentDescription = "Pick date")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Picker("Category", form.category, CATEGORIES, Modifier.fillMaxWidth()) { form.category = it }

            OutlinedTextField(
                value = form.note,
                onValueChange = { form.note = it },
                label = { Text("Note (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(onClick = ::save, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Save receipt") }

            if (triedSave && (form.merchant.isBlank() || form.amount.isBlank())) {
                Text(
                    "Saved with missing details. It's marked \"Needs review\" in the list.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (ocrText.isNotBlank()) {
                var showText by remember { mutableStateOf(false) }
                TextButton(onClick = { showText = !showText }) {
                    Text(if (showText) "Hide scanned text" else "Show scanned text")
                }
                if (showText) Text(ocrText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now()).toEpochDay() * 86_400_000L,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        form.date = LocalDate.ofEpochDay(it / 86_400_000L).format(ReceiptParser.displayDate)
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete receipt?") },
            text = { Text("The photo and its details will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    original?.let { r -> scope.launch { repository.delete(r); onDone() } }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(label: String, value: String, options: List<String>, modifier: Modifier, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onPick(option); expanded = false })
            }
        }
    }
}

@Composable
private fun VatSection(
    form: Form,
    rateErrors: List<Boolean>,
    amountErrors: List<Boolean>,
    autoVat: (Int) -> Long?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("VAT", style = MaterialTheme.typography.titleSmall)
        if (form.vat.isEmpty()) {
            Text(
                "No VAT found on the receipt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        form.vat.forEachIndexed { i, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RateField(row.rate, rateErrors.getOrElse(i) { false }, Modifier.weight(1f)) {
                    form.vat[i] = row.copy(rate = it)
                }
                OutlinedTextField(
                    value = row.amount,
                    onValueChange = { form.vat[i] = row.copy(amount = it) },
                    label = { Text("VAT amount") },
                    placeholder = autoVat(i)?.let { auto -> { Text(plainAmount(auto)) } },
                    singleLine = true,
                    isError = amountErrors.getOrElse(i) { false },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1.2f),
                )
                IconButton(onClick = { form.vat.removeAt(i) }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove VAT line")
                }
            }
        }
        Row {
            TextButton(onClick = { form.vat += VatRow(if (form.vat.isEmpty()) "8.1" else "", "") }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text("Add VAT rate")
            }
            TextButton(onClick = { form.vat.clear(); form.vat += VatRow("0", "0.00") }) {
                Text("0% VAT / import")
            }
        }
    }
}

/** Rate in percent, typed freely or picked from the common Swiss/EU rates. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RateField(value: String, isError: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text("Rate") },
            suffix = { Text("%") },
            singleLine = true,
            isError = isError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Vat.COMMON_RATES_BP.forEach { bp ->
                DropdownMenuItem(
                    text = { Text(if (bp == 0) "0% (exempt / import)" else Vat.formatRate(bp)) },
                    onClick = { onChange(Vat.formatRate(bp).removeSuffix("%")); expanded = false },
                )
            }
        }
    }
}
