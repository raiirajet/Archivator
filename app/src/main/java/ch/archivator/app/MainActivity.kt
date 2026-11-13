package ch.archivator.app

import android.Manifest
import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val forest = Color(0xFF145C4F)
private val mint = Color(0xFFE8F5EC)
private val ink = Color(0xFF18362E)
private val muted = Color(0xFF657A73)
private val canvas = Color(0xFFF5F8F6)
private val coral = Color(0xFFE27B52)

class MainActivity : FragmentActivity() {
    private lateinit var database: ArchiveDb
    private var docs by mutableStateOf<List<ArchiveDoc>>(emptyList())
    private var tab by mutableIntStateOf(0)
    private var editing by mutableStateOf<String?>(null)
    private var locked by mutableStateOf(false)
    private var fingerprintEnabled by mutableStateOf(false)
    private var remindersEnabled by mutableStateOf(true)
    private var importBusy by mutableStateOf(false)
    private var cameraUri: Uri? = null
    private var backupPassword: String? = null
    private var restorePassword: String? = null

    private val scannerResult = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val uri = scan?.pdf?.uri ?: scan?.pages?.firstOrNull()?.imageUri
            if (uri == null) toast("Keine Scan-Datei erhalten") else importDocument(uri, if (scan?.pdf != null) "application/pdf" else "image/jpeg")
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.take(20).forEach { importDocument(it) }
    }

    private val cameraResult = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraUri
        if (success && uri != null) importDocument(uri, "image/jpeg")
        else toast("Fotoaufnahme abgebrochen")
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toast("Fristen erscheinen in der App; Benachrichtigungen sind nicht erlaubt")
    }

    private val backupPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val password = backupPassword
        backupPassword = null
        if (uri != null && password != null) {
            lifecycleScope.launch {
                importBusy = true
                val error = withContext(Dispatchers.IO) {
                    runCatching { BackupEngine.export(this@MainActivity, database, uri, password) }.exceptionOrNull()
                }
                importBusy = false
                toast(if (error == null) "Verschlüsseltes Backup gespeichert" else "Backup fehlgeschlagen: ${error.message}")
            }
        }
    }

    private val restorePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val password = restorePassword
        restorePassword = null
        if (uri != null && password != null) {
            lifecycleScope.launch {
                importBusy = true
                val result = withContext(Dispatchers.IO) {
                    runCatching { BackupEngine.restore(this@MainActivity, database, uri, password) }
                }
                importBusy = false
                refresh()
                toast(result.fold(
                    { "$it Dokument(e) wiederhergestellt" },
                    { "Wiederherstellung fehlgeschlagen: ${it.message}" }
                ))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = ArchiveDb(this)
        fingerprintEnabled = getPreferences(MODE_PRIVATE).getBoolean("biometric", false)
        remindersEnabled = getPreferences(MODE_PRIVATE).getBoolean("reminders", true)
        locked = fingerprintEnabled
        refresh()
        if (remindersEnabled) scheduleDeadlines(this)
        window.statusBarColor = AndroidColor.rgb(20, 92, 79)
        window.navigationBarColor = AndroidColor.WHITE
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = forest, onPrimary = Color.White,
                secondary = coral, background = canvas, surface = Color.White,
                onSurface = ink
            )) {
                if (locked) LockScreen { authenticate() } else AppScreen()
            }
        }
        if (locked) authenticate()
        if (intent?.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (uri != null) importDocument(uri, intent.type)
        }
    }

    private fun refresh() { docs = database.all() }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun importDocument(uri: Uri, type: String? = null) {
        lifecycleScope.launch {
            importBusy = true
            val (doc, warning) = ArchiveEngine.import(this@MainActivity, database, uri, type)
            importBusy = false
            refresh()
            if (warning != null) toast(warning)
            if (doc != null) editing = doc.id
            else if (warning != null) toast(warning)
        }
    }

    private fun scan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF, GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { scannerResult.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener {
                toast("Scanner nicht verfügbar – Fotoaufnahme wird geöffnet")
                openCamera()
            }
    }

    private fun openCamera() {
        val file = File(cacheDir, "camera/${System.currentTimeMillis()}.jpg")
        file.parentFile?.mkdirs()
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        cameraUri = uri
        cameraResult.launch(uri)
    }

    private fun openFile(d: ArchiveDoc, share: Boolean) {
        val file = File(ArchiveEngine.docsDir(this), d.file)
        if (!file.isFile) { toast("Datei nicht gefunden"); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = if (share) Intent(Intent.ACTION_SEND).apply {
            type = d.mime; putExtra(Intent.EXTRA_STREAM, uri)
        } else Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, d.mime)
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(if (share) Intent.createChooser(intent, "Dokument teilen") else intent)
        } catch (_: ActivityNotFoundException) { toast("Keine App zum Öffnen dieses Dateityps gefunden") }
    }

    private fun deleteDoc(d: ArchiveDoc) {
        database.remove(d.id)
        File(ArchiveEngine.docsDir(this), d.file).delete()
        editing = null
        refresh()
        toast("Dokument gelöscht")
    }

    private fun authenticate() {
        if (!fingerprintEnabled) { locked = false; return }
        val biometric = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    locked = false
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) toast(errString.toString())
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Archivator entsperren")
            .setSubtitle("Dein persönliches Haushaltsarchiv")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        runCatching { biometric.authenticate(info) }.onFailure { toast("Entsperren fehlgeschlagen: ${it.message}") }
    }

    private fun setBiometric(enabled: Boolean) {
        if (enabled) {
            val can = BiometricManager.from(this).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            if (can != BiometricManager.BIOMETRIC_SUCCESS) {
                toast("Bitte zuerst biometrische Entsperrung oder Gerätesperre einrichten")
                return
            }
        }
        fingerprintEnabled = enabled
        getPreferences(MODE_PRIVATE).edit().putBoolean("biometric", enabled).apply()
        toast(if (enabled) "App-Sperre aktiviert" else "App-Sperre deaktiviert")
    }

    private fun setReminders(enabled: Boolean) {
        remindersEnabled = enabled
        getPreferences(MODE_PRIVATE).edit().putBoolean("reminders", enabled).apply()
        if (enabled) {
            scheduleDeadlines(this)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            androidx.work.WorkManager.getInstance(this).cancelUniqueWork("archive-deadlines")
        }
    }

    @Composable
    private fun AppScreen() {
        var showImport by remember { mutableStateOf(false) }
        val selected = editing?.let { id -> docs.firstOrNull { it.id == id } }
        if (selected != null) {
            DocumentEditor(
                doc = selected,
                onBack = { editing = null },
                onSave = { database.put(it); refresh(); editing = null; toast("Dokument gespeichert") },
                onDelete = { deleteDoc(selected) },
                onOpen = { openFile(selected, false) },
                onShare = { openFile(selected, true) },
                onPickDate = { initial, callback -> chooseDate(initial, callback) }
            )
            return
        }
        Scaffold(
            containerColor = canvas,
            topBar = { BrandHeader() },
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    val names = listOf("Übersicht", "Archiv", "Fristen", "Mehr")
                    val icons = listOf(Icons.Outlined.Home, Icons.Outlined.FolderOpen, Icons.Outlined.Event, Icons.Outlined.Settings)
                    names.forEachIndexed { i, name ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i },
                            label = { Text(name, fontSize = 11.sp) },
                            icon = { Icon(icons[i], contentDescription = null) })
                    }
                }
            },
            floatingActionButton = {
                if (tab != 3) ExtendedFloatingActionButton(
                    onClick = { showImport = true }, containerColor = forest, contentColor = Color.White,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text("Dokument") }
                )
            }
        ) { inner ->
            Box(Modifier.padding(inner).fillMaxSize()) {
                when (tab) {
                    0 -> Dashboard(docs, onOpen = { editing = it.id }, onScan = { scan() }, onImport = { showImport = true })
                    1 -> DocumentList(docs, onOpen = { editing = it.id })
                    2 -> Deadlines(docs, onOpen = { editing = it.id })
                    3 -> SettingsPage(
                        docs.size, fingerprintEnabled, remindersEnabled,
                        onFingerprint = ::setBiometric, onReminder = ::setReminders,
                        onBackup = { backupPassword = it; backupPicker.launch("Archivator-${LocalDate.now()}.arc") },
                        onRestore = { restorePassword = it; restorePicker.launch(arrayOf("*/*")) }
                    )
                }
                if (importBusy) {
                    Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                        Card { Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                            Spacer(Modifier.width(16.dp))
                            Text("Dokument wird verarbeitet …")
                        } }
                    }
                }
            }
        }
        if (showImport) AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("Dokument hinzufügen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImportAction(Icons.Outlined.DocumentScanner, "Smart-Scanner", "Mehrseitig scannen & zuschneiden") {
                        showImport = false; scan()
                    }
                    ImportAction(Icons.Outlined.PhotoCamera, "Foto aufnehmen", "Kamera als Alternative") {
                        showImport = false; openCamera()
                    }
                    ImportAction(Icons.Outlined.UploadFile, "PDF oder Bild importieren", "Downloads, Dateien und Galerie") {
                        showImport = false; filePicker.launch(arrayOf("application/pdf", "image/*"))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showImport = false }) { Text("Schliessen") } }
        )
    }

    private fun chooseDate(initial: String, onResult: (String) -> Unit) {
        val day = runCatching { LocalDate.parse(initial) }.getOrElse { LocalDate.now() }
        DatePickerDialog(this, { _, y, m, d ->
            onResult(LocalDate.of(y, m + 1, d).toString())
        }, day.year, day.monthValue - 1, day.dayOfMonth).show()
    }
}

@Composable
private fun BrandHeader() {
    Column(Modifier.fillMaxWidth().background(forest).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(38.dp).background(Color.White.copy(alpha = .15f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Inventory2, null, tint = Color.White, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Archivator", fontWeight = FontWeight.Bold, fontSize = 21.sp, color = Color.White)
                Text("Dein digitaler Haushalt", fontSize = 11.sp, color = Color.White.copy(alpha = .78f))
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Outlined.VerifiedUser, null, tint = Color.White.copy(alpha = .75f))
        }
    }
}

@Composable
private fun ImportAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = canvas, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = forest, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, color = muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Dashboard(docs: List<ArchiveDoc>, onOpen: (ArchiveDoc) -> Unit, onScan: () -> Unit, onImport: () -> Unit) {
    val today = LocalDate.now()
    val pending = docs.count { it.category == "Rechnung" && it.status !in listOf("Bezahlt", "Erledigt") }
    val soon = docs.count { d ->
        val date = runCatching { LocalDate.parse(d.due) }.getOrNull()
        date != null && !date.isAfter(today.plusDays(7)) && d.status !in listOf("Bezahlt", "Erledigt")
    }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Column {
                Text("Guten Tag 👋", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Alles Wichtige an einem Ort.", color = muted)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Dokumente", docs.size.toString(), Icons.Outlined.Description, Modifier.weight(1f))
                StatCard("Offene Rechnungen", pending.toString(), Icons.Outlined.ReceiptLong, Modifier.weight(1f))
                StatCard("Fristen", soon.toString(), Icons.Outlined.Event, Modifier.weight(1f))
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = forest), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    Text("Weniger Papier. Mehr Überblick.", color = Color.White,
                        fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Einmal scannen, automatisch auslesen, sofort wiederfinden.",
                        color = Color.White.copy(alpha = .85f), fontSize = 13.sp)
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = onScan, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = forest)) {
                        Icon(Icons.Outlined.DocumentScanner, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Jetzt scannen")
                    }
                }
            }
        }
        item {
            Text("Schnellzugriff", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickAction(Icons.Outlined.UploadFile, "Importieren", Modifier.weight(1f), onImport)
                QuickAction(Icons.Outlined.ReceiptLong, "Rechnungen", Modifier.weight(1f), onImport)
                QuickAction(Icons.Outlined.HealthAndSafety, "Krankenkasse", Modifier.weight(1f), onImport)
            }
        }
        item { Text("Zuletzt archiviert", fontWeight = FontWeight.Bold, fontSize = 17.sp) }
        if (docs.isEmpty()) item {
            EmptyState("Noch keine Dokumente", "Fotografiere eine Quittung oder importiere ein PDF.")
        } else items(docs.take(5), key = { it.id }) { d -> DocRow(d, onClick = { onOpen(d) }) }
        item { Spacer(Modifier.height(78.dp)) }
    }
}

@Composable
private fun StatCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(12.dp)) {
            Icon(icon, null, tint = forest, modifier = Modifier.size(23.dp))
            Spacer(Modifier.height(10.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 25.sp, color = ink)
            Text(label, color = muted, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2)
        }
    }
}

@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, color = Color.White, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(vertical = 17.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = forest, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, fontSize = 11.sp, color = ink, maxLines = 1)
        }
    }
}

@Composable
private fun DocumentList(docs: List<ArchiveDoc>, onOpen: (ArchiveDoc) -> Unit) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Alle") }
    val filtered = docs.filter { d ->
        (category == "Alle" || d.category == category) &&
            (query.isBlank() || listOf(d.title, d.tags, d.ocr, d.merchant, d.person, d.amount, d.note)
                .any { it.contains(query, ignoreCase = true) })
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(16.dp),
            label = { Text("Dokumente durchsuchen") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true, shape = RoundedCornerShape(15.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            categories.forEach { cat ->
                FilterChip(selected = category == cat, onClick = { category = cat },
                    label = { Text(cat, fontSize = 12.sp) })
            }
        }
        Text("${filtered.size} Dokumente", modifier = Modifier.padding(16.dp), fontSize = 13.sp, color = muted)
        if (filtered.isEmpty()) EmptyState("Keine Treffer", "Probiere einen anderen Suchbegriff oder Filter.")
        else LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 95.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(filtered, key = { it.id }) { DocRow(it) { onOpen(it) } }
        }
    }
}

@Composable
private fun DocRow(d: ArchiveDoc, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = Color.White) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).background(mint, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center) {
                Text(categoryIcons[d.category] ?: "📄", fontSize = 21.sp)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text("${d.category} · ${if (d.person.isBlank()) "Privat" else d.person} · ${d.created.asDate()}",
                    color = muted, fontSize = 11.sp, maxLines = 1)
            }
            if (d.amount.isNotBlank()) Text("${d.amount} CHF", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Icon(Icons.Outlined.ChevronRight, null, tint = muted)
        }
    }
}

@Composable
private fun Deadlines(docs: List<ArchiveDoc>, onOpen: (ArchiveDoc) -> Unit) {
    val today = LocalDate.now()
    val due = docs.mapNotNull { d ->
        val date = runCatching { LocalDate.parse(d.due) }.getOrNull()
        if (date != null && d.status !in listOf("Bezahlt", "Erledigt")) d to date else null
    }.sortedBy { it.second }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Fristen & Termine", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Überfälliges und anstehende Zahlungen", color = muted, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
        }
        if (due.isEmpty()) item { EmptyState("Keine offenen Fristen", "Hinterlege bei Dokumenten ein Fälligkeitsdatum.") }
        else items(due) { (d, date) ->
            val days = java.time.temporal.ChronoUnit.DAYS.between(today, date)
            Card(onClick = { onOpen(d) }, colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Event, null, tint = if (days < 0) coral else forest)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")),
                            color = muted, fontSize = 12.sp)
                    }
                    Text(when {
                        days < 0 -> "${-days} T. überfällig"
                        days == 0L -> "Heute"
                        else -> "in $days Tagen"
                    }, color = if (days < 0) coral else forest,
                        fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Inventory2, null, tint = forest.copy(alpha = .55f), modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = muted, fontSize = 12.sp)
    }
}

private fun Long.asDate(): String = java.time.Instant.ofEpochMilli(this)
    .atZone(java.time.ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN))

@Composable
private fun DocumentEditor(
    doc: ArchiveDoc,
    onBack: () -> Unit,
    onSave: (ArchiveDoc) -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onPickDate: (String, (String) -> Unit) -> Unit
) {
    var title by remember(doc.id) { mutableStateOf(doc.title) }
    var category by remember(doc.id) { mutableStateOf(doc.category) }
    var status by remember(doc.id) { mutableStateOf(doc.status) }
    var tags by remember(doc.id) { mutableStateOf(doc.tags) }
    var person by remember(doc.id) { mutableStateOf(doc.person) }
    var amount by remember(doc.id) { mutableStateOf(doc.amount) }
    var due by remember(doc.id) { mutableStateOf(doc.due) }
    var purchased by remember(doc.id) { mutableStateOf(doc.purchased) }
    var warranty by remember(doc.id) { mutableStateOf(doc.warrantyMonths.toString()) }
    var note by remember(doc.id) { mutableStateOf(doc.note) }
    var ocr by remember(doc.id) { mutableStateOf(doc.ocr) }
    var confirmDelete by remember { mutableStateOf(false) }
    val warrantyEnds = runCatching {
        if (purchased.isNotBlank() && (warranty.toIntOrNull() ?: 0) > 0)
            LocalDate.parse(purchased).plusMonths(warranty.toLong()).toString()
        else ""
    }.getOrDefault("")
    Scaffold(
        containerColor = canvas,
        topBar = {
            Row(Modifier.fillMaxWidth().background(forest)
                .windowInsetsPadding(WindowInsets.statusBars).height(60.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück", tint = Color.White)
                }
                Text("Dokument bearbeiten", color = Color.White, fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    onSave(doc.copy(title = title.ifBlank { "Dokument" }, category = category, status = status,
                        tags = tags, person = person, amount = amount, due = due.trim(), purchased = purchased.trim(),
                        warrantyMonths = warranty.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                        note = note, ocr = ocr))
                }) { Text("Speichern", color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(17.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Visibility, null); Spacer(Modifier.width(6.dp)); Text("Öffnen")
                    }
                    OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Share, null); Spacer(Modifier.width(6.dp)); Text("Teilen")
                    }
                }
            }
            item { FormField("Dokumentname", title) { title = it } }
            item {
                Text("Kategorie", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    categories.drop(1).forEach {
                        FilterChip(selected = category == it, onClick = { category = it }, label = { Text(it) })
                    }
                }
            }
            item {
                Text("Bearbeitungsstand", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    states.forEach {
                        FilterChip(selected = status == it, onClick = { status = it }, label = { Text(it) })
                    }
                }
            }
            item { FormField("Person / Haushaltsmitglied", person) { person = it } }
            item { FormField("Schlagwörter (#tags, durch Komma getrennt)", tags) { tags = it } }
            item { FormField("Betrag (CHF)", amount, KeyboardType.Decimal) { amount = it } }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { FormField("Fälligkeitsdatum (JJJJ-MM-TT)", due) { due = it } }
                    IconButton(onClick = { onPickDate(due) { due = it } }) {
                        Icon(Icons.Outlined.CalendarMonth, "Datum wählen", tint = forest)
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { FormField("Kaufdatum (JJJJ-MM-TT)", purchased) { purchased = it } }
                    IconButton(onClick = { onPickDate(purchased) { purchased = it } }) {
                        Icon(Icons.Outlined.CalendarMonth, "Kaufdatum wählen", tint = forest)
                    }
                }
            }
            item { FormField("Garantie (Monate)", warranty, KeyboardType.Number) { warranty = it } }
            if (warrantyEnds.isNotBlank()) item {
                Text("Garantieende: $warrantyEnds", color = forest, fontWeight = FontWeight.SemiBold)
            }
            item { FormField("Notizen", note, lines = 3) { note = it } }
            item {
                Text("Erkannter Text (editierbar)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("OCR kann Fehler enthalten. Korrigierter Text bleibt durchsuchbar.",
                    fontSize = 12.sp, color = muted)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = ocr, onValueChange = { ocr = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 145.dp),
                    minLines = 5, maxLines = 12)
            }
            item {
                TextButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Outlined.DeleteOutline, null, tint = coral)
                    Spacer(Modifier.width(7.dp))
                    Text("Dokument unwiderruflich löschen", color = coral)
                }
            }
            item { Spacer(Modifier.height(22.dp)) }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Dokument löschen?") },
        text = { Text("Datei, Texterkennung und alle Metadaten werden dauerhaft entfernt.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Löschen", color = coral) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } }
    )
}

@Composable
private fun FormField(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, lines: Int = 1,
                      onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(),
        label = { Text(label, fontSize = 13.sp) },
        singleLine = lines == 1, minLines = lines, maxLines = maxOf(lines, 5),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = RoundedCornerShape(13.dp))
}

@Composable
private fun SettingsPage(
    count: Int, fingerprint: Boolean, reminders: Boolean,
    onFingerprint: (Boolean) -> Unit, onReminder: (Boolean) -> Unit,
    onBackup: (String) -> Unit, onRestore: (String) -> Unit
) {
    var passwordDialog by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            Text("Einstellungen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Privat, sicher und ohne Benutzerkonto", color = muted)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(17.dp)) {
                    Text("Mein Archiv", fontWeight = FontWeight.Bold)
                    Text("$count Dokumente · nur auf diesem Gerät", color = muted, fontSize = 13.sp)
                    Spacer(Modifier.height(7.dp))
                    Text("Die Dateien liegen im privaten App-Speicher. Android schützt ihn durch die Gerätespeicherverschlüsselung.",
                        color = muted, fontSize = 12.sp)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Fingerprint, null, tint = forest)
                        Spacer(Modifier.width(10.dp))
                        Text("Biometrische App-Sperre", modifier = Modifier.weight(1f))
                        Switch(checked = fingerprint, onCheckedChange = onFingerprint)
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Notifications, null, tint = forest)
                        Spacer(Modifier.width(10.dp))
                        Text("Fristen erinnern", modifier = Modifier.weight(1f))
                        Switch(checked = reminders, onCheckedChange = onReminder)
                    }
                }
            }
        }
        item {
            Text("Datensicherung", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("AES-256-GCM-verschlüsselt. Passwort sicher aufbewahren – ohne Passwort sind die Daten nicht wiederherstellbar.",
                color = muted, fontSize = 12.sp)
        }
        item {
            Button(onClick = { password = ""; passwordDialog = "backup" },
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.CloudUpload, null)
                Spacer(Modifier.width(8.dp))
                Text("Verschlüsseltes Backup exportieren")
            }
        }
        item {
            OutlinedButton(onClick = { password = ""; passwordDialog = "restore" },
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Restore, null)
                Spacer(Modifier.width(8.dp))
                Text("Backup wiederherstellen")
            }
        }
        item {
            Text("Archivator v1.0 · Offline-first", fontSize = 12.sp, color = muted)
            Text("Der ML-Kit-Scanner benötigt Google Play-Dienste; die Texterkennung läuft lokal.",
                color = muted, fontSize = 12.sp)
        }
    }
    if (passwordDialog != null) {
        val kind = passwordDialog
        AlertDialog(
            onDismissRequest = { passwordDialog = null },
            title = { Text(if (kind == "backup") "Backup-Passwort festlegen" else "Backup entschlüsseln") },
            text = {
                Column {
                    Text("Mindestens 8 Zeichen. Das Passwort wird nicht gespeichert.")
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = password, onValueChange = { password = it },
                        label = { Text("Passwort") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton(enabled = password.length >= 8, onClick = {
                    passwordDialog = null
                    if (kind == "backup") onBackup(password) else onRestore(password)
                }) { Text("Weiter") }
            },
            dismissButton = { TextButton(onClick = { passwordDialog = null }) { Text("Abbrechen") } }
        )
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Surface(color = forest, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(68.dp), tint = Color.White)
            Spacer(Modifier.height(24.dp))
            Text("Archivator ist gesperrt", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("Dein privates Archiv bleibt geschützt.", color = Color.White.copy(alpha = .8f))
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUnlock, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = forest)) {
                Text("Entsperren")
            }
        }
    }
}
