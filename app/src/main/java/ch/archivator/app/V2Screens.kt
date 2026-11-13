package ch.archivator.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val navy2 = Color(0xFF113C69)
private val baby2 = Color(0xFFDCEFFF)
private val sky2 = Color(0xFFB4DCFF)
private val subtle2 = Color(0xFFF3F8FF)
private val grey2 = Color(0xFF65758A)
private val red2 = Color(0xFFC34B52)

private fun money(s: String): Double = s.replace("'", "").replace(",", ".").toDoubleOrNull() ?: 0.0
private fun swissAmount(d: Double): String = String.format(java.util.Locale.US, "%.2f", d)
private fun dateOrNull(s: String): LocalDate? = runCatching { LocalDate.parse(s) }.getOrNull()
private fun warrantyEnd(d: ArchiveDoc): LocalDate? =
    dateOrNull(d.purchased)?.takeIf { d.warrantyMonths > 0 }?.plusMonths(d.warrantyMonths.toLong())
private fun recurringDate(d: ArchiveDoc, today: LocalDate): LocalDate? {
    var day = dateOrNull(d.due) ?: return null
    if (d.recurringMonths < 1) return day
    var n = 0
    while (day.isBefore(today) && n++ < 360) day = day.plusMonths(d.recurringMonths.toLong())
    return day
}

@Composable
private fun SmallHeadline(title: String, hint: String? = null) {
    Column {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
        if (hint != null) Text(hint, color = grey2, fontSize = 12.sp)
    }
}

@Composable
private fun HeroStat(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, null, tint = navy2, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(10.dp))
            Text(value, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 21.sp)
            Text(label, color = grey2, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun BlueTile(label: String, detail: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(17.dp), color = Color.White) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(baby2, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center) { Icon(icon, null, tint = navy2) }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(detail, color = grey2, fontSize = 12.sp)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = navy2)
        }
    }
}

@Composable
fun V2Dashboard(
    docs: List<ArchiveDoc>,
    onOpen: (ArchiveDoc) -> Unit,
    onScan: () -> Unit,
    onBrowse: (String) -> Unit
) {
    val today = LocalDate.now()
    val unpaid = docs.filter { it.category == "Rechnung" && it.status !in listOf("Bezahlt", "Erledigt") }
    val soon = docs.count {
        val date = recurringDate(it, today)
        date != null && !date.isAfter(today.plusDays(7)) && it.status !in listOf("Bezahlt", "Erledigt")
    }
    val expiring = docs.count {
        warrantyEnd(it)?.let { date -> !date.isBefore(today) && !date.isAfter(today.plusDays(60)) } ?: false
    }
    val insurance = docs.filter { it.category == "Krankenkasse" }
    val waiting = insurance.filter { it.status !in listOf("Erledigt", "Bezahlt") }
    val families = docs.map { it.person }.filter { it.isNotBlank() }.distinct().sorted()
    LazyColumn(contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)) {
        item {
            Text("Mein digitaler Haushalt", fontSize = 23.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
            Text("Alles Wichtige auf einen Blick", color = grey2)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                HeroStat("Dokumente", docs.size.toString(), Icons.Outlined.FolderCopy, Modifier.weight(1f))
                HeroStat("Offen", unpaid.size.toString(), Icons.Outlined.ReceiptLong, Modifier.weight(1f))
                HeroStat("Diese Woche", soon.toString(), Icons.Outlined.Event, Modifier.weight(1f))
            }
        }
        item {
            Card(shape = RoundedCornerShape(23.dp),
                colors = CardDefaults.cardColors(containerColor = navy2)) {
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    Text("Ein Scan. Alles archiviert.", color = Color.White,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text("Dokument scannen, automatisch erkennen und Angaben prüfen.",
                        color = baby2, fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onScan, colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = navy2)) {
                        Icon(Icons.Outlined.DocumentScanner, null)
                        Spacer(Modifier.width(9.dp))
                        Text("Dokument scannen", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item { SmallHeadline("Deine Bereiche") }
        item {
            BlueTile("Rechnungen", "${unpaid.size} offen · ${swissAmount(unpaid.sumOf { money(it.amount) })} CHF",
                Icons.Outlined.AccountBalanceWallet) { onBrowse("Rechnung") }
        }
        item {
            BlueTile("Garantien", "$expiring laufen innerhalb von 60 Tagen ab",
                Icons.Outlined.VerifiedUser) { onBrowse("Garantie") }
        }
        item {
            BlueTile("Krankenkasse", "${waiting.size} Belege nicht abgeschlossen",
                Icons.Outlined.HealthAndSafety) { onBrowse("Krankenkasse") }
        }
        item {
            BlueTile("Familienordner", if (families.isEmpty()) "Personen beim Dokument zuordnen"
                else families.joinToString(" · "), Icons.Outlined.Groups) { onBrowse("Alle") }
        }
        if (insurance.isNotEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(17.dp)) {
                    SmallHeadline("Krankenkassen-Cockpit", "Alle Beträge zur Kontrolle")
                    Spacer(Modifier.height(10.dp))
                    Text("Eingereicht: ${swissAmount(insurance.sumOf { money(it.amount) })} CHF",
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text("Erstattet: ${swissAmount(insurance.sumOf { money(it.reimbursed) })} CHF",
                        color = MaterialTheme.colorScheme.onSurface)
                    Text("Selbstbehalt: ${swissAmount(insurance.sumOf { money(it.deductible) })} CHF",
                        color = MaterialTheme.colorScheme.onSurface)
                    Text("Angaben sind manuell bearbeitbar und keine Leistungszusage.", fontSize = 11.sp, color = grey2)
                }
            }
        }
        item { SmallHeadline("Zuletzt archiviert") }
        if (docs.isEmpty()) item { Text("Noch keine Dokumente vorhanden.", color = grey2) }
        else items(docs.take(4), key = { it.id }) { d ->
            V2DocCard(d) { onOpen(d) }
        }
        item { Spacer(Modifier.height(90.dp)) }
    }
}

@Composable
private fun DocumentThumb(d: ArchiveDoc) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(initialValue = null, d.id, d.file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val full = PdfPages.pageBitmap(ctx, d, 0)
                val small = Bitmap.createScaledBitmap(full, 96, 132, true)
                if (small !== full) full.recycle()
                small
            }.getOrNull()
        }
    }
    Box(Modifier.size(46.dp).background(baby2, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp!!.asImageBitmap(), contentDescription = "Dokumentvorschau",
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Icon(Icons.Outlined.Description, contentDescription = null, tint = navy2)
    }
}

@Composable
private fun V2DocCard(d: ArchiveDoc, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            DocumentThumb(d)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text("${d.category} · ${d.person.ifBlank { "Privat" }}",
                    color = grey2, fontSize = 12.sp)
            }
            if (d.amount.isNotBlank()) Text(d.amount, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
            Icon(Icons.Outlined.ChevronRight, null, tint = grey2)
        }
    }
}

@Composable
fun V2DocumentList(docs: List<ArchiveDoc>, initialCategory: String,
                   onOpen: (ArchiveDoc) -> Unit) {
    var query by remember { mutableStateOf("") }
    var category by remember(initialCategory) { mutableStateOf(initialCategory) }
    var person by remember { mutableStateOf("Alle") }
    var status by remember { mutableStateOf("Alle") }
    val people = listOf("Alle") + docs.map { it.person }.filter { it.isNotBlank() }.distinct().sorted()
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    val ctx = LocalContext.current
    var searchIds by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(query, docs) {
        if (query.isBlank()) searchIds = null
        else {
            delay(180)
            searchIds = withContext(Dispatchers.IO) {
                ArchiveDb(ctx).use { it.searchIds(query) }
            }
        }
    }
    val filtered = docs.filter { d ->
        (category == "Alle" || d.category == category) &&
        (person == "Alle" || person == d.person) &&
        (status == "Alle" || status == d.status) &&
        (query.isBlank() || (searchIds != null && d.id in searchIds!!) ||
            words.all { w ->
                listOf(d.title, d.tags, d.person, d.note, d.category,
                    d.amount, d.purchased, d.due, d.policyNumber, d.assetName,
                    d.cancellationDate).any { it.contains(w, ignoreCase = true) }
            })
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it },
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 15.dp),
            shape = RoundedCornerShape(14.dp), singleLine = true,
            label = { Text("Suche nach Text, Betrag, Name …") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) })
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 15.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            categories.forEach { c ->
                FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
            }
        }
        if (people.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 15.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                people.forEach { p ->
                    FilterChip(selected = p == person, onClick = { person = p },
                        leadingIcon = if (p == person) { { Icon(Icons.Outlined.Person, null, Modifier.size(15.dp)) } } else null,
                        label = { Text(if (p == "Alle") "Alle Personen" else p) })
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 15.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("Alle", "Offen", "Bezahlt", "Eingereicht", "In Bearbeitung", "Teilweise erstattet", "Erledigt").forEach { s ->
                FilterChip(selected = s == status, onClick = { status = s }, label = { Text(s) })
            }
        }
        Text("${filtered.size} Dokument(e)", color = grey2, modifier = Modifier.padding(15.dp), fontSize = 12.sp)
        if (filtered.isEmpty()) Text("Keine passenden Dokumente.", color = grey2, modifier = Modifier.padding(24.dp))
        else LazyColumn(contentPadding = PaddingValues(start = 15.dp, end = 15.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(filtered, key = { it.id }) { V2DocCard(it) { onOpen(it) } }
        }
    }
}

@Composable
fun V2Deadlines(docs: List<ArchiveDoc>, onOpen: (ArchiveDoc) -> Unit) {
    val now = LocalDate.now()
    val due = docs.mapNotNull { d ->
        val day = recurringDate(d, now) ?: return@mapNotNull null
        if (d.status in listOf("Bezahlt", "Erledigt") && d.recurringMonths == 0) return@mapNotNull null
        Triple(d, day, "Fälligkeit")
    }
    val warranties = docs.mapNotNull { d ->
        warrantyEnd(d)?.let { Triple(d, it, "Garantieende") }
    }
    val events = (due + warranties).sortedBy { it.second }
    LazyColumn(contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)) {
        item {
            SmallHeadline("Fristen & Garantien", "Rechnungen, Zahlungen und Garantieabläufe")
            Spacer(Modifier.height(8.dp))
        }
        if (events.isEmpty()) item { Text("Noch keine Termine hinterlegt.", color = grey2) }
        else items(events, key = { it.first.id + it.third }) { (d, date, kind) ->
            val days = ChronoUnit.DAYS.between(now, date)
            Card(onClick = { onOpen(d) }, shape = RoundedCornerShape(17.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (kind == "Garantieende") Icons.Outlined.VerifiedUser else Icons.Outlined.Event,
                        null, tint = if (days < 0) red2 else navy2)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$kind · $date", color = grey2, fontSize = 12.sp)
                        if (d.recurringMonths > 0) Text("Alle ${d.recurringMonths} Monate",
                            color = grey2, fontSize = 11.sp)
                    }
                    Text(when {
                        days < 0 -> "${-days} T. spät"
                        days == 0L -> "Heute"
                        else -> "in $days T."
                    }, color = if (days < 0) red2 else navy2, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold)
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun EditorField(title: String, value: String, keyboard: KeyboardType = KeyboardType.Text,
                        minLines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange,
        label = { Text(title, fontSize = 13.sp) },
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        singleLine = minLines == 1, minLines = minLines, maxLines = maxOf(6, minLines),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}

@Composable
fun V2DocumentEditor(
    doc: ArchiveDoc,
    allDocs: List<ArchiveDoc> = emptyList(),
    onBack: () -> Unit,
    onSave: (ArchiveDoc) -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onPreview: () -> Unit,
    onVersions: () -> Unit,
    onPickDate: (String, (String) -> Unit) -> Unit
) {
    var title by remember(doc.id) { mutableStateOf(doc.title) }
    var category by remember(doc.id) { mutableStateOf(doc.category) }
    var status by remember(doc.id) { mutableStateOf(doc.status) }
    var person by remember(doc.id) { mutableStateOf(doc.person) }
    var tags by remember(doc.id) { mutableStateOf(doc.tags) }
    var amount by remember(doc.id) { mutableStateOf(doc.amount) }
    var merchant by remember(doc.id) { mutableStateOf(doc.merchant) }
    var due by remember(doc.id) { mutableStateOf(doc.due) }
    var purchase by remember(doc.id) { mutableStateOf(doc.purchased) }
    var warranty by remember(doc.id) { mutableStateOf(doc.warrantyMonths.toString()) }
    var iban by remember(doc.id) { mutableStateOf(doc.iban) }
    var reference by remember(doc.id) { mutableStateOf(doc.reference) }
    var invoice by remember(doc.id) { mutableStateOf(doc.invoiceNumber) }
    var recurring by remember(doc.id) { mutableStateOf(doc.recurringMonths.toString()) }
    var reimbursed by remember(doc.id) { mutableStateOf(doc.reimbursed) }
    var deductible by remember(doc.id) { mutableStateOf(doc.deductible) }
    var ocr by remember(doc.id) { mutableStateOf(doc.ocr) }
    var note by remember(doc.id) { mutableStateOf(doc.note) }
    var groupId by remember(doc.id) { mutableStateOf(doc.groupId) }
    var taxRelevant by remember(doc.id) { mutableStateOf(doc.taxRelevant) }
    var cancellationDate by remember(doc.id) { mutableStateOf(doc.cancellationDate) }
    var policyNumber by remember(doc.id) { mutableStateOf(doc.policyNumber) }
    var assetName by remember(doc.id) { mutableStateOf(doc.assetName) }
    var paidDate by remember(doc.id) { mutableStateOf(doc.paidDate) }
    var reimbursedDate by remember(doc.id) { mutableStateOf(doc.reimbursedDate) }
    var deleteConfirm by remember { mutableStateOf(false) }
    val guarantee = runCatching { LocalDate.parse(purchase).plusMonths(warranty.toLong()) }.getOrNull()
    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().background(sky2)
                .windowInsetsPadding(WindowInsets.statusBars).height(60.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück", tint = navy2) }
                Text("Dokument bearbeiten", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f), fontSize = 17.sp)
                TextButton(onClick = {
                    onSave(doc.copy(title = title.ifBlank { "Dokument" }, category = category,
                        status = status, person = person, tags = tags, amount = amount,
                        merchant = merchant, due = due, purchased = purchase,
                        warrantyMonths = warranty.toIntOrNull()?.coerceIn(0, 600) ?: 0,
                        iban = iban, reference = reference, invoiceNumber = invoice,
                        recurringMonths = recurring.toIntOrNull()?.coerceIn(0, 120) ?: 0,
                        reimbursed = reimbursed, deductible = deductible,
                        groupId = groupId.trim(), taxRelevant = taxRelevant,
                        cancellationDate = cancellationDate, policyNumber = policyNumber,
                        assetName = assetName, paidDate = paidDate,
                        reimbursedDate = reimbursedDate, note = note, ocr = ocr))
                }) { Text("Speichern", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) }
            }
        }, containerColor = MaterialTheme.colorScheme.background
    ) { inner ->
        LazyColumn(Modifier.padding(inner), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPreview, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = navy2)) {
                        Icon(Icons.Outlined.Preview, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Vorschau")
                    }
                    OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Share, null)
                        Spacer(Modifier.width(6.dp)); Text("Teilen")
                    }
                    IconButton(onClick = onOpen) { Icon(Icons.Outlined.OpenInNew, "Extern öffnen", tint = navy2) }
                }
            }
            item {
                OutlinedButton(onClick = onVersions, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.History, null)
                    Spacer(Modifier.width(7.dp))
                    Text("Frühere PDF-Versionen anzeigen / wiederherstellen")
                }
            }
            item { SmallHeadline("Allgemeine Angaben", "Automatisch erkannte Werte bitte überprüfen") }
            item { EditorField("Dokumentname", title) { title = it } }
            item {
                Text("Kategorie", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    categories.drop(1).forEach { c ->
                        FilterChip(selected = c == category, onClick = { category = c }, label = { Text(c) })
                    }
                }
            }
            item {
                Text("Status", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    states.forEach { s ->
                        FilterChip(selected = status == s, onClick = { status = s }, label = { Text(s) })
                    }
                }
            }
            item { EditorField("Haushaltsmitglied / Ordner", person) { person = it } }
            item { EditorField("Tags (durch Komma getrennt)", tags) { tags = it } }
            item { EditorField("Vorgang (Quittung, Garantie, Rechnung verbinden)", groupId) { groupId = it } }
            if (allDocs.any { it.groupId.isNotBlank() && it.groupId != groupId }) item {
                Text("Bestehende Vorgänge", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    allDocs.map { it.groupId }.filter { it.isNotBlank() }.distinct().take(15).forEach { g ->
                        SuggestionChip(onClick = { groupId = g }, label = { Text(g) })
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = taxRelevant, onCheckedChange = { taxRelevant = it })
                    Text("Für Steuerordner markieren", color = MaterialTheme.colorScheme.onSurface)
                }
            }
            item { EditorField("Händler / Empfänger", merchant) { merchant = it } }
            item { EditorField("Betrag in CHF", amount, KeyboardType.Decimal) { amount = it } }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { EditorField("Fälligkeitsdatum (JJJJ-MM-TT)", due) { due = it } }
                    IconButton(onClick = { onPickDate(due) { due = it } }) {
                        Icon(Icons.Outlined.Event, "Fälligkeitsdatum wählen", tint = navy2)
                    }
                }
            }
            if (category == "Rechnung" || category == "Abonnement") {
                item { SmallHeadline("Schweizer Rechnung", "QR-Rechnungsdaten werden offline erkannt") }
                item { EditorField("IBAN / QR-IBAN", iban) { iban = it } }
                item { EditorField("Zahlungsreferenz", reference) { reference = it } }
                item { EditorField("Rechnungsnummer", invoice) { invoice = it } }
                item { EditorField("Bezahlt am (JJJJ-MM-TT)", paidDate) { paidDate = it } }
                item { EditorField("Wiederholung alle X Monate (0 = einmalig)", recurring,
                    KeyboardType.Number) { recurring = it } }
                if (doc.qrPayload.isNotBlank()) item {
                    Surface(color = baby2, shape = RoundedCornerShape(14.dp)) {
                        Text("Schweizer QR-Rechnung erkannt. Bitte Betrag, IBAN und Referenz vor der Zahlung kontrollieren.",
                            modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                    }
                }
            }
            if (category == "Garantie" || category == "Quittung") {
                item { SmallHeadline("Garantieassistent", "Kaufdatum und Garantiedauer") }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { EditorField("Kaufdatum (JJJJ-MM-TT)", purchase) { purchase = it } }
                        IconButton(onClick = { onPickDate(purchase) { purchase = it } }) {
                            Icon(Icons.Outlined.Event, "Kaufdatum wählen", tint = navy2)
                        }
                    }
                }
                item { EditorField("Garantie (Monate)", warranty, KeyboardType.Number) { warranty = it } }
                if (guarantee != null) item {
                    Text("Garantieende: $guarantee", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                }
            }
            if (category in listOf("Versicherung", "Vertrag", "Abonnement", "Fahrzeug", "Wohnen")) {
                item { SmallHeadline("Vertrag, Objekt und Fristen") }
                item { EditorField("Policen-/Vertragsnummer", policyNumber) { policyNumber = it } }
                item { EditorField("Objekt oder Gerät / Fahrzeug", assetName) { assetName = it } }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            EditorField("Kündigung / Prüfung (JJJJ-MM-TT)", cancellationDate) { cancellationDate = it }
                        }
                        IconButton(onClick = { onPickDate(cancellationDate) { cancellationDate = it } }) {
                            Icon(Icons.Outlined.Event, "Frist wählen", tint = navy2)
                        }
                    }
                }
            }
            if (category == "Krankenkasse") {
                item { SmallHeadline("Krankenkasse", "Leistungsabrechnung und Rückerstattung") }
                item { EditorField("Erstatteter Betrag CHF", reimbursed, KeyboardType.Decimal) { reimbursed = it } }
                item { EditorField("Erstattet am (JJJJ-MM-TT)", reimbursedDate) { reimbursedDate = it } }
                item { EditorField("Selbstbehalt / Franchise CHF", deductible, KeyboardType.Decimal) { deductible = it } }
                item {
                    Text("Noch nicht erstattet: ${swissAmount((money(amount) - money(reimbursed) - money(deductible)).coerceAtLeast(0.0))} CHF",
                        color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                }
            }
            item { SmallHeadline("Weitere Angaben") }
            item { EditorField("Notizen", note, minLines = 3) { note = it } }
            item { EditorField("OCR-Volltext (korrigierbar)", ocr, minLines = 5) { ocr = it } }
            item {
                TextButton(onClick = { deleteConfirm = true }) {
                    Icon(Icons.Outlined.DeleteOutline, null, tint = red2)
                    Spacer(Modifier.width(8.dp))
                    Text("Dokument unwiderruflich löschen", color = red2)
                }
            }
            item { Spacer(Modifier.height(25.dp)) }
        }
    }
    if (deleteConfirm) AlertDialog(
        onDismissRequest = { deleteConfirm = false },
        title = { Text("Dokument löschen?") },
        text = { Text("Die Datei und die dazugehörigen Daten werden dauerhaft gelöscht.") },
        confirmButton = { TextButton(onClick = { deleteConfirm = false; onDelete() }) { Text("Löschen") } },
        dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("Abbrechen") } }
    )
}
