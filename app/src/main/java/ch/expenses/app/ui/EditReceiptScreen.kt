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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

    companion object {
        val Saver = listSaver<Form, String>(
            save = { listOf(it.merchant, it.date, it.amount, it.currency, it.category, it.note) },
            restore = { v ->
                Form().apply { merchant = v[0]; date = v[1]; amount = v[2]; currency = v[3]; category = v[4]; note = v[5] }
            },
        )
    }
}

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
            }
        } else if (newPhotoPath != null) {
            val text = ReceiptScanner(context).readText(File(newPhotoPath))
            val parsed = ReceiptParser.parse(text)
            ocrText = text
            form.merchant = parsed.merchant ?: ""
            form.date = (parsed.date ?: LocalDate.now()).format(ReceiptParser.displayDate)
            form.amount = parsed.totalCents?.let { ReceiptParser.formatAmount(it).replace("'", "") } ?: ""
            form.currency = parsed.currency ?: "CHF"
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

    fun save() {
        triedSave = true
        if (amountError || dateError) return
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
