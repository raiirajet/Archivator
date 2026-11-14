package ch.archivator.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.vector.ImageVector
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
    val default = listOf("Scan", "Rechnungen", "Garantien", "Krankenkasse", "Familienordner", "Finanzen", "Versicherungen")
    val all = default + listOf("Fahrzeug", "Haushalt", "Steuern", "Abonnements", "Zeitachse", "Letzte Dokumente")
    private const val key = "home_widgets"
    private const val columnsKey = "home_columns"

    fun columns(ctx: Context): Int =
        ctx.getSharedPreferences("archivator-dashboard", Context.MODE_PRIVATE)
            .getInt(columnsKey, 2).coerceIn(2, 6)

    fun saveColumns(ctx: Context, columns: Int) {
        ctx.getSharedPreferences("archivator-dashboard", Context.MODE_PRIVATE)
            .edit().putInt(columnsKey, columns.coerceIn(2, 6)).apply()
    }

    fun load(ctx: Context): List<String> {
        val prefs = ctx.getSharedPreferences("archivator-dashboard", Context.MODE_PRIVATE)
        val raw = prefs.getString(key, null)
        if (raw == null) {
            prefs.edit().putBoolean("scan_tile_migrated_v1", true).apply()
            return default
        }
        val saved = raw.split("|").filter { it in all }.distinct()
        if (prefs.getBoolean("scan_tile_migrated_v1", false)) return saved
        // Add the new Scan tile once for existing installations; later hiding it remains respected.
        val migrated = (listOf("Scan") + saved).distinct()
        prefs.edit().putString(key, migrated.joinToString("|"))
            .putBoolean("scan_tile_migrated_v1", true).apply()
        return migrated
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
private fun DashboardTile(
    label: String,
    detail: String,
    icon: ImageVector,
    columns: Int,
    isDragging: Boolean,
    isDropTarget: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tiny = columns >= 5
    val dense = columns >= 4
    val foreground = MaterialTheme.colorScheme.onSurface
    val main = MaterialTheme.colorScheme.primary
    val height = when {
        tiny -> 88.dp
        dense -> 112.dp
        columns == 3 -> 126.dp
        else -> 142.dp
    }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(height),
        shape = RoundedCornerShape(if (tiny) 13.dp else 19.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = if (isDragging) 12.dp else 0.dp,
        border = if (isDropTarget) BorderStroke(2.dp, main) else null
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(if (tiny) 6.dp else if (dense) 8.dp else 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(icon, null, tint = main, modifier = Modifier.size(
                if (tiny) 18.dp else if (dense) 20.dp else 25.dp
            ))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    label, color = foreground, fontWeight = FontWeight.SemiBold,
                    fontSize = if (tiny) 9.sp else if (dense) 11.sp else 13.sp,
                    lineHeight = if (tiny) 11.sp else if (dense) 13.sp else 16.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                if (!tiny) Text(
                    detail, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = if (dense) 9.sp else 11.sp,
                    lineHeight = if (dense) 11.sp else 13.sp,
                    maxLines = if (dense) 1 else 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun dashboardIcon(widget: String): ImageVector = when (widget) {
    "Scan" -> Icons.Outlined.DocumentScanner
    "Rechnungen" -> Icons.Outlined.ReceiptLong
    "Garantien" -> Icons.Outlined.VerifiedUser
    "Krankenkasse" -> Icons.Outlined.HealthAndSafety
    "Familienordner" -> Icons.Outlined.Groups
    "Finanzen" -> Icons.Outlined.AccountBalanceWallet
    "Versicherungen" -> Icons.Outlined.Security
    "Fahrzeug" -> Icons.Outlined.DirectionsCar
    "Haushalt" -> Icons.Outlined.Home
    "Steuern" -> Icons.Outlined.RequestQuote
    "Abonnements" -> Icons.Outlined.Autorenew
    "Zeitachse" -> Icons.Outlined.Timeline
    "Letzte Dokumente" -> Icons.Outlined.FolderOpen
    else -> Icons.Outlined.FolderOpen
}

@Composable
fun V3Dashboard(
    docs: List<ArchiveDoc>,
    widgets: List<String>,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    onWidgetsReordered: (List<String>) -> Unit,
    onOpen: (ArchiveDoc) -> Unit,
    onScan: () -> Unit,
    onBrowse: (String) -> Unit,
    onSection: (String) -> Unit
) {
    val today = LocalDate.now()
    val unpaid = docs.filter {
        it.category == "Rechnung" && it.status !in listOf("Bezahlt", "Erledigt")
    }
    val insurance = docs.filter {
        it.category == "Krankenkasse" && it.status !in listOf("Erledigt", "Bezahlt")
    }
    val expiring = docs.count { doc ->
        val end = dateOf(doc.purchased)?.plusMonths(doc.warrantyMonths.toLong())
        doc.warrantyMonths > 0 && end != null &&
            !end.isBefore(today) && !end.isAfter(today.plusDays(60))
    }
    val people = docs.map { it.person }.filter { it.isNotBlank() }.distinct()
    val gridState = rememberLazyGridState()
    val haptics = LocalHapticFeedback.current
    var draggedWidget by remember { mutableStateOf<String?>(null) }
    var dropTarget by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val latestWidgets = rememberUpdatedState(widgets)
    val latestReorder = rememberUpdatedState(onWidgetsReordered)
    val latestColumns = rememberUpdatedState(columns)
    val latestColumnsChange = rememberUpdatedState(onColumnsChange)

    fun summary(widget: String): String = when (widget) {
        "Scan" -> docs.count { it.category == "Scan" }.toString() + " unzugeordnete Scans"
        "Rechnungen" -> "${unpaid.size} offen · ${chf(unpaid.sumOf { amountOf(it.amount) })}"
        "Garantien" -> "$expiring laufen bald ab"
        "Krankenkasse" -> "${insurance.size} offene Belege"
        "Familienordner" -> "${people.size} Personen"
        "Finanzen" -> "Ausgaben und Belege"
        "Versicherungen" -> "Policen und Fristen"
        "Fahrzeug" -> "Service und Dokumente"
        "Haushalt" -> "Verträge und Geräte"
        "Steuern" -> "Belege nach Steuerjahr"
        "Abonnements" -> "Wiederkehrende Kosten"
        "Zeitachse" -> "Alle wichtigen Termine"
        "Letzte Dokumente" -> "${docs.size} archivierte Dokumente"
        else -> "Bereich öffnen"
    }

    fun open(widget: String) {
        when (widget) {
            "Scan" -> onBrowse("Scan")
            "Rechnungen" -> onBrowse("Rechnung")
            "Garantien" -> onBrowse("Garantie")
            "Krankenkasse" -> onBrowse("Krankenkasse")
            "Familienordner", "Letzte Dokumente" -> onBrowse("Alle")
            else -> onSection(widget)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns.coerceIn(2, 6)),
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var accumulatedZoom = 1f
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    // Leave single-finger scrolling and long-press tile dragging untouched.
                    if (pressed.size >= 2) {
                        accumulatedZoom *= event.calculateZoom()
                        if (accumulatedZoom >= 1.18f) {
                            val next = (latestColumns.value - 1).coerceAtLeast(2)
                            if (next != latestColumns.value) latestColumnsChange.value(next)
                            accumulatedZoom = 1f
                        } else if (accumulatedZoom <= 0.85f) {
                            val next = (latestColumns.value + 1).coerceAtMost(6)
                            if (next != latestColumns.value) latestColumnsChange.value(next)
                            accumulatedZoom = 1f
                        }
                        pressed.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        },
        state = gridState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "dashboard:title") {
            Column {
                Text("Mein digitaler Haushalt", fontSize = 24.sp,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text("${docs.size} Dokumente sicher archiviert",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "dashboard:scan") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = babyBlue),
                shape = RoundedCornerShape(23.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Ein Scan. Alles im Griff.",
                            color = blueInk, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                            lineHeight = 24.sp)
                        Spacer(Modifier.height(5.dp))
                        Text("Scannen, erkennen und sicher archivieren.",
                            color = blueInk, fontSize = 12.sp, lineHeight = 17.sp)
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = onScan,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = blueInk, contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp)
                        ) {
                            Icon(Icons.Outlined.DocumentScanner, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Jetzt scannen", color = Color.White)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Outlined.Description, null,
                        modifier = Modifier.size(58.dp),
                        tint = blueInk.copy(alpha = 0.18f))
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "dashboard:stats") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                listOf(
                    unpaid.size.toString() to "Offene Rechnungen",
                    expiring.toString() to "Garantien bald fällig",
                    insurance.size.toString() to "Kassenbelege offen"
                ).forEach { (value, label) ->
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = 92.dp).padding(12.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(value, fontWeight = FontWeight.Bold, fontSize = 23.sp)
                            Text(label, fontSize = 11.sp, lineHeight = 13.sp)
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "dashboard:widget-header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Meine Übersicht", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Mit zwei Fingern zoomen · lange drücken zum Verschieben",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        gridItems(widgets, key = { "tile:$it" }) { widget ->
            val dragging = draggedWidget == widget
            val target = dropTarget == widget && draggedWidget != widget
            DashboardTile(
                label = widget,
                detail = summary(widget),
                icon = dashboardIcon(widget),
                columns = columns,
                isDragging = dragging,
                isDropTarget = target,
                modifier = Modifier
                    .zIndex(if (dragging) 20f else 0f)
                    .graphicsLayer {
                        if (dragging) {
                            translationX = dragOffset.x
                            translationY = dragOffset.y
                            scaleX = 1.04f
                            scaleY = 1.04f
                        }
                    }
                    .pointerInput(widget, columns) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                draggedWidget = widget
                                dropTarget = widget
                                dragOffset = Offset.Zero
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                if (draggedWidget != widget) return@detectDragGesturesAfterLongPress
                                dragOffset += amount
                                val visible = gridState.layoutInfo.visibleItemsInfo
                                val origin = visible.firstOrNull { it.key == "tile:$widget" }
                                if (origin != null) {
                                    val x = origin.offset.x + origin.size.width / 2f + dragOffset.x
                                    val y = origin.offset.y + origin.size.height / 2f + dragOffset.y
                                    val hovered = visible.firstOrNull {
                                        val key = it.key as? String
                                        key != null && key.startsWith("tile:") &&
                                            x >= it.offset.x && x <= it.offset.x + it.size.width &&
                                            y >= it.offset.y && y <= it.offset.y + it.size.height
                                    }
                                    dropTarget = (hovered?.key as? String)?.removePrefix("tile:")
                                        ?: widget
                                }
                            },
                            onDragEnd = {
                                val from = latestWidgets.value.indexOf(widget)
                                val to = latestWidgets.value.indexOf(dropTarget)
                                if (from >= 0 && to >= 0 && from != to) {
                                    val reordered = latestWidgets.value.toMutableList()
                                    reordered.add(to, reordered.removeAt(from))
                                    latestReorder.value(reordered)
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                draggedWidget = null
                                dropTarget = null
                                dragOffset = Offset.Zero
                            },
                            onDragCancel = {
                                draggedWidget = null
                                dropTarget = null
                                dragOffset = Offset.Zero
                            }
                        )
                    },
                onClick = { if (draggedWidget == null) open(widget) }
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "dashboard:search") {
            OutlinedButton(
                onClick = { onSection("Archivator fragen") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Search, null)
                Spacer(Modifier.width(8.dp))
                Text("Archivator fragen")
            }
        }
        if (docs.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "dashboard:recent-title") {
                Text("Zuletzt archiviert", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            items(docs.take(3).size, span = { GridItemSpan(maxLineSpan) },
                key = { i -> "recent:${docs[i].id}" }) { index ->
                val doc = docs[index]
                BriefDoc(doc, onClick = { onOpen(doc) }, description = doc.amount)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "dashboard:bottom") {
            Spacer(Modifier.height(72.dp))
        }
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
