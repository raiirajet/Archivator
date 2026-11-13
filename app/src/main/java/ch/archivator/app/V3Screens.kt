package ch.archivator.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val babyBlue = Color(0xFFDAEEFF)
private val blueInk = Color(0xFF103A65)
private val blueMuted = Color(0xFF6B7D90)

object V3Sections {
    val all = listOf(
        "Finanzen", "Versicherungen", "Fahrzeug", "Haushalt",
        "Abonnements", "Steuern", "Zeitachse", "Archivator fragen",
        "Dokumentenverknüpfungen"
    )
}

object HomeWidgets {
    val default = listOf("Rechnungen", "Garantien", "Krankenkasse", "Familienordner", "Finanzen", "Versicherungen")
    val all = default + listOf("Fahrzeug", "Haushalt", "Steuern", "Abonnements", "Zeitachse", "Letzte Dokumente")
    private const val key = "home_widgets"

    fun load(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences("archivator-dashboard", Context.MODE_PRIVATE).getString(key, null)
        return if (raw == null) default else raw.split("|").filter { it in all }.distinct()
    }
    fun save(ctx: Context, values: List<String>) {
        ctx.getSharedPreferences("archivator-dashboard", Context.MODE_PRIVATE).edit()
            .putString(key, values.filter { it in all }.distinct().joinToString("|")).apply()
    }

    @Composable
    fun Editor(current: List<String>, onUpdate: (List<String>) -> Unit) {
        var expanded by remember { mutableStateOf(false) }
        Column {
            TextButton(onClick = { expanded = !expanded }) {
                Icon(Icons.Outlined.DashboardCustomize, null)
                Spacer(Modifier.width(8.dp))
                Text("Startseite anpassen")
                Spacer(Modifier.width(5.dp))
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (expanded) {
                Text("Ein-/ausblenden und mit Pfeilen sortieren", fontSize = 12.sp)
                all.forEach { item ->
                    val selected = item in current
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = selected, onCheckedChange = { enabled ->
                            onUpdate(if (enabled) current + item else current - item)
                        })
                        Text(item, Modifier.weight(1f), fontSize = 14.sp)
                        if (selected) {
                            val idx = current.indexOf(item)
                            IconButton(enabled = idx > 0, onClick = {
                                val list = current.toMutableList()
                                list.add(idx - 1, list.removeAt(idx))
                                onUpdate(list)
                            }) { Icon(Icons.Outlined.ArrowUpward, "Nach oben") }
                            IconButton(enabled = idx < current.lastIndex, onClick = {
                                val list = current.toMutableList()
                                list.add(idx + 1, list.removeAt(idx))
                                onUpdate(list)
                            }) { Icon(Icons.Outlined.ArrowDownward, "Nach unten") }
                        }
                    }
                }
            }
        }
    }
}

private fun amountOf(s: String): Double = s.replace("'", "").replace("’", "")
    .replace(",", ".").toDoubleOrNull() ?: 0.0
private fun chf(amount: Double): String = String.format(java.util.Locale.ROOT, "%.2f", amount) + " CHF"
private fun dateOf(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()
private fun archivedAt(d: ArchiveDoc): LocalDate =
    Instant.ofEpochMilli(d.created).atZone(ZoneId.systemDefault()).toLocalDate()
private fun relevantYear(d: ArchiveDoc): Int =
    (dateOf(d.purchased) ?: dateOf(d.due) ?: archivedAt(d)).year

@Composable
private fun AreaCard(title: String, detail: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(detail, fontSize = 12.sp, color = blueMuted)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun BriefDoc(d: ArchiveDoc, onClick: () -> Unit, description: String = "") {
    AreaCard(d.title, (if (description.isNotBlank()) "$description · " else "") +
        "${d.category} · ${d.person.ifBlank { "Privat" }}", onClick)
}

@Composable
fun V3Dashboard(
    docs: List<ArchiveDoc>, widgets: List<String>,
    onOpen: (ArchiveDoc) -> Unit, onScan: () -> Unit,
    onBrowse: (String) -> Unit, onSection: (String) -> Unit
) {
    val today = LocalDate.now()
    val unpaid = docs.filter { it.category == "Rechnung" && it.status !in listOf("Bezahlt", "Erledigt") }
    val ins = docs.filter { it.category == "Krankenkasse" && it.status != "Erledigt" }
    val expiring = docs.count { doc ->
        val end = dateOf(doc.purchased)?.plusMonths(doc.warrantyMonths.toLong())
        doc.warrantyMonths > 0 && end != null && !end.isBefore(today) && !end.isAfter(today.plusDays(60))
    }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        item {
            Text("Mein digitaler Haushalt", fontSize = 24.sp,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text("${docs.size} Dokumente sicher archiviert", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = babyBlue),
                shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Ein Scan. Alles im Griff.", color = blueInk,
                        fontWeight = FontWeight.Bold, fontSize = 21.sp)
                    Text("Scannen, automatisch erkennen und prüfen.", color = blueInk, fontSize = 13.sp)
                    Spacer(Modifier.height(15.dp))
                    Button(onClick = onScan, colors = ButtonDefaults.buttonColors(containerColor = blueInk)) {
                        Icon(Icons.Outlined.DocumentScanner, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Jetzt scannen")
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(13.dp)) {
                        Text(unpaid.size.toString(), fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        Text("Offene Rechnungen", fontSize = 11.sp)
                    }
                }
                Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(13.dp)) {
                        Text(expiring.toString(), fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        Text("Garantien bald fällig", fontSize = 11.sp)
                    }
                }
                Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(13.dp)) {
                        Text(ins.size.toString(), fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        Text("Kassenbelege offen", fontSize = 11.sp)
                    }
                }
            }
        }
        if (widgets.isNotEmpty()) item {
            Text("Meine Übersicht", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        items(widgets, key = { it }) { widget ->
            when (widget) {
                "Rechnungen" -> AreaCard("Rechnungen", "${unpaid.size} offen · ${chf(unpaid.sumOf { amountOf(it.amount) })}") { onBrowse("Rechnung") }
                "Garantien" -> AreaCard("Garantien", "$expiring laufen innerhalb von 60 Tagen ab") { onBrowse("Garantie") }
                "Krankenkasse" -> AreaCard("Krankenkasse", "${ins.size} noch nicht abgeschlossen") { onBrowse("Krankenkasse") }
                "Familienordner" -> AreaCard("Familienordner", docs.map { it.person }.filter { it.isNotBlank() }.distinct().joinToString(", ").ifBlank { "Personen zuordnen" }) { onBrowse("Alle") }
                "Letzte Dokumente" -> {
                    docs.take(4).forEach { doc -> BriefDoc(doc, { onOpen(doc) }, doc.amount) }
                }
                else -> AreaCard(widget, "Dokumente und Übersicht öffnen") { onSection(widget) }
            }
        }
        item {
            OutlinedButton(onClick = { onSection("Archivator fragen") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Archivator fragen")
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
fun V3SectionScreen(
    section: String, docs: List<ArchiveDoc>,
    onBack: () -> Unit, onOpen: (ArchiveDoc) -> Unit,
    onScan: () -> Unit, onSection: (String) -> Unit,
    onTaxExport: (Int) -> Unit, onCsvExport: (Int) -> Unit,
    onSaveDoc: (ArchiveDoc) -> Unit
) {
    var year by remember(section) { mutableIntStateOf(LocalDate.now().year) }
    var question by remember { mutableStateOf("") }
    var exportConfirm by remember { mutableStateOf(false) }
    var exportKind by remember { mutableStateOf("tax") }
    val selected = when (section) {
        "Versicherungen" -> docs.filter { it.category == "Versicherung" || it.tags.contains("versicherung", true) }
        "Fahrzeug" -> docs.filter { it.category == "Fahrzeug" || it.tags.contains("fahrzeug", true) }
        "Haushalt" -> docs.filter { it.category == "Wohnen" || it.tags.contains("haushalt", true) }
        "Abonnements" -> docs.filter { it.recurringMonths > 0 || it.category == "Abonnement" }
        "Steuern" -> docs.filter { relevantYear(it) == year && (it.taxRelevant || it.category == "Steuern" || it.tags.contains("steuer", true)) }
        "Finanzen" -> docs.filter { relevantYear(it) == year && it.amount.isNotBlank() }
        else -> docs
    }
    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                    .height(58.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück")
                    }
                    Text(section, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        floatingActionButton = {
            if (section !in listOf("Archivator fragen", "Zeitachse", "Dokumentenverknüpfungen")) {
                FloatingActionButton(onClick = onScan) { Icon(Icons.Outlined.Add, "Dokument scannen") }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (section in listOf("Finanzen", "Steuern")) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { year-- }) { Icon(Icons.Outlined.ChevronLeft, "Vorjahr") }
                        Text(year.toString(), modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        IconButton(onClick = { year++ }) { Icon(Icons.Outlined.ChevronRight, "Folgejahr") }
                    }
                }
            }
            when (section) {
                "Finanzen" -> {
                    item {
                        Text("Erfasste Belegbeträge", fontWeight = FontWeight.Bold)
                        Text(chf(selected.sumOf { amountOf(it.amount) }),
                            fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("Rechnungen und Quittungen können denselben Kauf betreffen. Dies ist keine Kontoumsatzanalyse.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            (1..12).forEach { month ->
                                val value = selected.filter { relevantYear(it) == year &&
                                    (dateOf(it.purchased) ?: dateOf(it.due) ?: archivedAt(it)).monthValue == month }
                                    .sumOf { amountOf(it.amount) }
                                if (value > 0.0) SuggestionChip(onClick = {}, label = { Text("$month: ${chf(value)}") })
                            }
                        }
                    }
                    item { Button(onClick = { exportKind = "csv"; exportConfirm = true }) { Text("Jahresübersicht als CSV") } }
                }
                "Steuern" -> {
                    item { Text("Steuerrelevante Belege für $year: ${selected.size}", fontWeight = FontWeight.Bold) }
                    item { Button(onClick = { exportKind = "tax"; exportConfirm = true }) {
                        Icon(Icons.Outlined.FolderZip, null); Spacer(Modifier.width(6.dp))
                        Text("Steuerbelege als ZIP exportieren")
                    } }
                    item { Text("Die Markierung ist eine Archivierungshilfe und keine Prüfung der steuerlichen Abzugsfähigkeit.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                "Archivator fragen" -> {
                    item {
                        OutlinedTextField(question, { question = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Was suchst du?") },
                            placeholder = { Text("Welche Rechnungen sind noch offen?") },
                            leadingIcon = { Icon(Icons.Outlined.Search, null) })
                        Text("Lokale Frage-Suche ohne Cloud. Kein generatives KI-Modell.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            listOf("Offene Rechnungen", "Krankenkassenbelege", "Garantien", "Auto",
                                "Steuerbelege").forEach { hint ->
                                SuggestionChip(onClick = { question = hint }, label = { Text(hint) })
                            }
                        }
                    }
                }
                "Zeitachse" -> item {
                    Text("Verlauf: archiviert, gekauft, fällig und Garantieende",
                        fontSize = 13.sp)
                }
                "Dokumentenverknüpfungen" -> item {
                    Text("Vorgänge verbinden Rechnungen, Quittungen und Garantien über eine gemeinsame Vorgangskennung.",
                        fontSize = 13.sp)
                }
                else -> item {
                    Text(when(section) {
                        "Versicherungen" -> "Policen, Prämien und Kündigungstermine an einem Ort"
                        "Fahrzeug" -> "Service, Reparaturen, Reifen, Prüfungen und Belege"
                        "Haushalt" -> "Mietvertrag, Nebenkosten, Geräte und Wartung"
                        "Abonnements" -> "Wiederkehrende Zahlungen und Kündigungsfristen"
                        else -> "Alle Dokumente dieses Bereichs"
                    }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val visible = if (section == "Archivator fragen") {
                val q = question.trim().lowercase()
                val tokens = q.split(Regex("[^\\p{L}\\p{N}]+"))
                    .filter { it.length >= 3 && it !in setOf("welche","meine","alle","habe","noch","sind","nach","offen") }
                docs.filter { d ->
                    val domain = when {
                        "rechnung" in q -> d.category == "Rechnung"
                        "krankenkass" in q -> d.category == "Krankenkasse"
                        "garant" in q -> d.warrantyMonths > 0 || d.category == "Garantie"
                        "steuer" in q -> d.taxRelevant || d.category == "Steuern"
                        else -> true
                    }
                    val open = "offen" !in q || d.status !in listOf("Bezahlt", "Erledigt")
                    val corpus = listOf(d.title,d.ocr,d.tags,d.merchant,d.person,d.category,d.note,
                        d.amount,d.invoiceNumber,d.reference).joinToString(" ").lowercase()
                    domain && open && (tokens.isEmpty() || tokens.any {
                        t -> corpus.contains(t) || t in setOf("rechnungen","garantien","krankenkassenbelege","steuerbelege")
                    })
                }
            } else selected
            if (section == "Zeitachse") {
                val events = docs.flatMap { d ->
                    buildList<Pair<LocalDate,String>> {
                        add(archivedAt(d) to "Archiviert")
                        dateOf(d.purchased)?.let { add(it to "Gekauft") }
                        dateOf(d.due)?.let { add(it to "Fällig") }
                        if (d.warrantyMonths > 0) dateOf(d.purchased)?.let { add(it.plusMonths(d.warrantyMonths.toLong()) to "Garantieende") }
                    }.map { Triple(it.first, d, it.second) }
                }.sortedByDescending { it.first }
                items(events.take(100), key = { "${it.second.id}-${it.third}-${it.first}" }) { (date, d, label) ->
                    BriefDoc(d, { onOpen(d) }, "$date · $label")
                }
            } else if (section == "Dokumentenverknüpfungen") {
                val groups = docs.filter { it.groupId.isNotBlank() }.groupBy { it.groupId }
                if (groups.isEmpty()) item {
                    Text("Noch keine Vorgänge. Vergib bei zusammengehörigen Dokumenten dieselbe Vorgangskennung.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                groups.forEach { (group, groupDocs) ->
                    item { Text(group, fontWeight = FontWeight.Bold) }
                    items(groupDocs, key = { it.id }) { BriefDoc(it, { onOpen(it) }) }
                }
            } else {
                if (visible.isEmpty()) item {
                    Text("Keine passenden Dokumente. Neue Dokumente kannst du einscannen oder im Archiv zuordnen.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(visible, key = { it.id }) { doc -> BriefDoc(doc, { onOpen(doc) }, doc.amount) }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
    if (exportConfirm) AlertDialog(
        onDismissRequest = { exportConfirm = false },
        title = { Text("Export bestätigen") },
        text = { Text(if (exportKind == "tax") {
            "Die ZIP-Datei enthält unverschlüsselte Steuerdokumente. Speichere sie nur an einem sicheren Ort."
        } else "Die CSV enthält Finanzdaten im Klartext. Speicherort sorgfältig wählen.") },
        confirmButton = { TextButton(onClick = {
            exportConfirm = false
            if (exportKind == "tax") onTaxExport(year) else onCsvExport(year)
        }) { Text("Exportieren") } },
        dismissButton = { TextButton(onClick = { exportConfirm = false }) { Text("Abbrechen") } }
    )
}
