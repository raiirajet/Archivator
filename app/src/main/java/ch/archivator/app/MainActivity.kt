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
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
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

private val forest = Color(0xFF113C69)
private val mint = Color(0xFFDFEFFF)
private val ink = Color(0xFF113C69)
private val muted = Color(0xFF657A73)
private val canvas = Color(0xFFF3F8FF)
private val coral = Color(0xFFE27B52)

class MainActivity : FragmentActivity() {
    private lateinit var database: ArchiveDb
    private var docs by mutableStateOf<List<ArchiveDoc>>(emptyList())
    private var tab by mutableIntStateOf(0)
    private var tabTrail by mutableStateOf<List<Int>>(emptyList())
    private var extraPage by mutableStateOf<String?>(null)
    private var extraTrail by mutableStateOf<List<String>>(emptyList())
    private var showVersionsFor by mutableStateOf<String?>(null)
    private var pdfExtractDocId: String? = null
    private var pdfExtractIndex: Int = 0
    private var autoBackupPassword: String? = null
    private var autoBackupRevision by mutableIntStateOf(0)
    private var confirmExit by mutableStateOf(false)
    private var darkAppearance by mutableStateOf(false)
    private var homeWidgets by mutableStateOf<List<String>>(HomeWidgets.default)

    private var editing by mutableStateOf<String?>(null)
    private var previewDoc by mutableStateOf<String?>(null)
    private var initialArchiveCategory by mutableStateOf("Alle")
    private var locked by mutableStateOf(false)
    private var fingerprintEnabled by mutableStateOf(false)
    private var remindersEnabled by mutableStateOf(true)
    private var importBusy by mutableStateOf(false)
    private var cameraUri: Uri? = null
    private var backupPassword: String? = null
    private var restorePassword: String? = null
    private var taxExportYear = LocalDate.now().year
    private var csvExportYear = LocalDate.now().year

    private fun changeTab(target: Int) {
        if (target == tab) return
        tabTrail = tabTrail + tab
        tab = target
    }
    private fun openSection(section: String) {
        if (extraPage != null && extraPage != section) extraTrail = extraTrail + extraPage!!
        extraPage = section
    }
    private fun backOneLevel() {
        when {
            previewDoc != null -> previewDoc = null
            editing != null -> editing = null
            extraPage != null -> {
                extraPage = extraTrail.lastOrNull()
                extraTrail = extraTrail.dropLast(1)
            }
            tabTrail.isNotEmpty() -> {
                tab = tabTrail.last()
                tabTrail = tabTrail.dropLast(1)
            }
            tab != 0 -> tab = 0
            else -> confirmExit = true
        }
    }


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

    private val pdfExtractPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            val docId = pdfExtractDocId
            if (uri != null && docId != null) {
                val doc = database.byId(docId)
                if (doc != null) lifecycleScope.launch {
                    importBusy = true
                    val result = withContext(Dispatchers.IO) {
                        runCatching { PdfPages.extract(this@MainActivity, doc, pdfExtractIndex, uri) }
                    }
                    importBusy = false
                    toast(if (result.isSuccess) "PDF-Seite exportiert"
                        else "PDF-Export fehlgeschlagen: ${result.exceptionOrNull()?.message}")
                }
            }
            pdfExtractDocId = null
        }

    private val autoBackupPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val password = autoBackupPassword
        autoBackupPassword = null
        if (uri != null && password != null) lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AutoBackups.configure(this@MainActivity, uri, password) }
            }
            if (result.isSuccess) autoBackupRevision++
            toast(if (result.isSuccess) "Automatische verschlüsselte Sicherungen eingerichtet"
                else "Automatisches Backup: ${result.exceptionOrNull()?.message}")
        }
    }

    private val taxPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) lifecycleScope.launch {
            importBusy = true
            val result = withContext(Dispatchers.IO) {
                runCatching { ArchiveExports.taxPackage(this@MainActivity, database, uri, taxExportYear) }
            }
            importBusy = false
            toast(result.fold({ "$it Steuerbelege exportiert" }, { "Steuerexport: ${it.message}" }))
        }
    }
    private val csvPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) lifecycleScope.launch {
            importBusy = true
            val result = withContext(Dispatchers.IO) {
                runCatching { ArchiveExports.financeCsv(this@MainActivity, database, uri, csvExportYear) }
            }
            importBusy = false
            toast(result.fold({ "CSV exportiert" }, { "CSV-Export: ${it.message}" }))
        }
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
        darkAppearance = getPreferences(MODE_PRIVATE).getBoolean("appearance_dark", false)
        homeWidgets = HomeWidgets.load(this)
        locked = fingerprintEnabled
        Vault.purgeCache(this)
        refresh()
        lifecycleScope.launch {
            importBusy = true
            val migration = withContext(Dispatchers.IO) {
                runCatching { Vault.migrate(this@MainActivity, database) }
            }
            importBusy = false
            migration.onFailure { toast("Verschlüsselungsmigration: ${it.message}") }
            refresh()
        }
        if (remindersEnabled) scheduleDeadlines(this)
        if (AutoBackups.enabled(this)) AutoBackups.schedule(this)
        window.statusBarColor = AndroidColor.rgb(172, 216, 246)
        window.navigationBarColor = if (darkAppearance) AndroidColor.BLACK else AndroidColor.WHITE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightNavigationBars = !darkAppearance
            isAppearanceLightStatusBars = !darkAppearance
        }
        setContent {
            MaterialTheme(colorScheme = if (darkAppearance) darkColorScheme(
                surfaceVariant = Color(0xFF253A51),
                onSurfaceVariant = Color(0xFFC2D0DF),
                primary = Color(0xFFACD8F6), onPrimary = Color(0xFF082949),
                secondary = Color(0xFFA9D6FF), surface = Color(0xFF13243A),
                background = Color(0xFF091726), onSurface = Color.White
            ) else lightColorScheme(
                primary = forest, onPrimary = Color.White,
                secondary = Color(0xFF85C4F5), background = canvas,
                surface = Color.White, onSurface = ink
            )) {
                if (locked) LockScreen { authenticate() } else AppScreen()
            }
        }
        if (locked) authenticate()
        processIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIncoming(intent)
    }

    override fun onStop() {
        super.onStop()
        if (fingerprintEnabled && !isChangingConfigurations) locked = true
    }

    private fun processIncoming(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) importDocument(uri, intent.type)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                uris?.take(30)?.forEach { importDocument(it, intent.type) }
            }
            Intent.ACTION_VIEW -> intent.data?.let { importDocument(it, intent.type) }
        }
    }

    private fun refresh() { docs = database.all() }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun importDocument(uri: Uri, type: String? = null) {
        lifecycleScope.launch {
            importBusy = true
            val (doc, warning) = ArchiveEngine.importDocument(this@MainActivity, database, uri, type)
            importBusy = false
            refresh()
            if (warning != null) toast(warning)
            if (doc != null) editing = doc.id
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
        lifecycleScope.launch {
            val fileResult = withContext(Dispatchers.IO) {
                runCatching { Vault.shareCopy(this@MainActivity, d) }
            }
            fileResult.onFailure { toast("Dokument kann nicht geöffnet werden: ${it.message}") }
            val file = fileResult.getOrNull() ?: return@launch
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
            val viewIntent = if (share) Intent(Intent.ACTION_SEND).apply {
                type = d.mime
                putExtra(Intent.EXTRA_STREAM, uri)
            } else Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, d.mime)
            }
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(if (share) Intent.createChooser(viewIntent, "Dokument teilen") else viewIntent)
            } catch (_: ActivityNotFoundException) {
                toast("Keine App zum Öffnen dieses Dateityps gefunden")
            }
        }
    }

    private fun deleteDoc(d: ArchiveDoc) {
        database.remove(d.id)
        File(ArchiveEngine.docsDir(this), d.file).delete()
        Vault.purgeVersions(this, d.id)
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
        BackHandler { backOneLevel() }
        var showImport by remember { mutableStateOf(false) }
        if (confirmExit) {
            AlertDialog(onDismissRequest = { confirmExit = false },
                title = { Text("Archivator verlassen?") },
                text = { Text("Deine archivierten Dokumente bleiben gespeichert.") },
                confirmButton = { TextButton(onClick = { confirmExit = false; finish() }) { Text("Schliessen") } },
                dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("In der App bleiben") } })
        }
        val historyDoc = showVersionsFor?.let { id -> docs.firstOrNull { it.id == id } }
        if (historyDoc != null) {
            val versions = remember(historyDoc.id, docs) { Vault.versions(this, historyDoc) }
            AlertDialog(onDismissRequest = { showVersionsFor = null },
                title = { Text("Vorherige Dateiversionen") },
                text = {
                    Column {
                        Text("Bis zu vier verschlüsselte ältere Dateiversionen werden auf diesem Gerät aufbewahrt.",
                            fontSize = 12.sp)
                        versions.forEach { version ->
                            TextButton(onClick = {
                                showVersionsFor = null
                                lifecycleScope.launch {
                                    importBusy = true
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            Vault.restoreVersion(this@MainActivity, database, historyDoc, version)
                                        }
                                    }
                                    importBusy = false
                                    refresh()
                                    toast(if (result.isSuccess) "Dateiversion wiederhergestellt"
                                        else "Wiederherstellung: ${result.exceptionOrNull()?.message}")
                                }
                            }) {
                                Text(java.time.Instant.ofEpochMilli(version.timestamp)
                                    .atZone(java.time.ZoneId.systemDefault())
                                    .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")))
                            }
                        }
                        if (versions.isEmpty()) Text("Noch keine älteren Versionen vorhanden.")
                    }
                },
                confirmButton = { TextButton(onClick = { showVersionsFor = null }) { Text("Schliessen") } })
        }
        val preview = previewDoc?.let { id -> docs.firstOrNull { it.id == id } }
        if (preview != null) {
            PdfPreviewScreen(this, preview, allDocs = docs,
                onBack = { previewDoc = null },
                onUpdated = { previewDoc = null; refresh(); toast("PDF aktualisiert") },
                onExtract = { index ->
                    pdfExtractDocId = preview.id
                    pdfExtractIndex = index
                    pdfExtractPicker.launch("Archivator-Seite-${index + 1}.pdf")
                })
            return
        }
        val selected = editing?.let { id -> docs.firstOrNull { it.id == id } }
        if (selected != null) {
            V2DocumentEditor(
                doc = selected,
                allDocs = docs,
                onBack = { editing = null },
                onSave = { database.put(it); CategoryMemory.remember(this@MainActivity, it); refresh(); editing = null; toast("Dokument gespeichert") },
                onDelete = { deleteDoc(selected) },
                onOpen = { openFile(selected, false) },
                onShare = { openFile(selected, true) },
                onPreview = { previewDoc = selected.id },
                onVersions = { showVersionsFor = selected.id },
                onPickDate = { initial, callback -> chooseDate(initial, callback) }
            )
            return
        }
        val extras = extraPage
        if (extras != null) {
            V3SectionScreen(
                section = extras, docs = docs,
                onBack = { backOneLevel() },
                onOpen = { editing = it.id },
                onScan = { scan() },
                onSection = ::openSection,
                onTaxExport = { taxExportYear = it; taxPicker.launch("Archivator-Steuern-$it.zip") },
                onCsvExport = { csvExportYear = it; csvPicker.launch("Archivator-Finanzen-$it.csv") },
                onSaveDoc = { database.put(it); refresh() }
            )
            return
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { BrandHeader() },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    val names = listOf("Übersicht", "Archiv", "Scannen", "Fristen", "Mehr")
                    val icons = listOf(Icons.Outlined.Home, Icons.Outlined.FolderOpen,
                        Icons.Outlined.DocumentScanner, Icons.Outlined.Event, Icons.Outlined.Settings)
                    names.forEachIndexed { i, name ->
                        val target = if (i >= 3) i - 1 else i
                        NavigationBarItem(
                            selected = i != 2 && tab == target,
                            onClick = {
                                if (i == 2) showImport = true
                                else {
                                    if (target == 1) initialArchiveCategory = "Alle"
                                    changeTab(target)
                                }
                            },
                            label = { Text(name, fontSize = 11.sp) },
                            icon = {
                                Icon(icons[i], contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        )
                    }
                }
            },
        ) { inner ->
            Box(Modifier.padding(inner).fillMaxSize()) {
                when (tab) {
                    0 -> V3Dashboard(docs, widgets = homeWidgets,
                        onOpen = { editing = it.id }, onScan = { scan() },
                        onBrowse = { initialArchiveCategory = it; changeTab(1); initialArchiveCategory = it },
                        onSection = ::openSection)
                    1 -> V2DocumentList(docs, initialCategory = initialArchiveCategory,
                        onOpen = { editing = it.id })
                    2 -> V2Deadlines(docs, onOpen = { editing = it.id })
                    3 -> SettingsPage(
                        docs.size, fingerprintEnabled, remindersEnabled,
                        onFingerprint = ::setBiometric, onReminder = ::setReminders,
                        onBackup = { backupPassword = it; backupPicker.launch("Archivator-${LocalDate.now()}.arc") },
                        onRestore = { restorePassword = it; restorePicker.launch(arrayOf("*/*")) },
                        onSection = ::openSection,
                        dark = darkAppearance,
                        onDark = {
                            window.navigationBarColor = if (it) AndroidColor.BLACK else AndroidColor.WHITE
                            WindowInsetsControllerCompat(window, window.decorView).apply {
                                isAppearanceLightNavigationBars = !it
                                isAppearanceLightStatusBars = !it
                            }
                            darkAppearance = it
                            getPreferences(MODE_PRIVATE).edit().putBoolean("appearance_dark", it).apply()
                        },
                        widgets = homeWidgets,
                        autoEnabled = autoBackupRevision >= 0 && AutoBackups.enabled(this@MainActivity),
                        autoLast = AutoBackups.lastResult(this@MainActivity),
                        onAutoBackup = { autoBackupPassword = it; autoBackupPicker.launch(null) },
                        onAutoDisable = { AutoBackups.disable(this@MainActivity); autoBackupRevision++; toast("Automatisches Backup deaktiviert") },
                        onWidgets = {
                            homeWidgets = it
                            HomeWidgets.save(this@MainActivity, it)
                        }
                    )                }
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
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.25f
    val header = if (isDark) Color(0xFF142B44) else Color(0xFFACD8F6)
    val foreground = if (isDark) Color.White else forest
    Column(Modifier.fillMaxWidth().background(header).windowInsetsPadding(WindowInsets.statusBars)) {
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
                Text("Archivator", fontWeight = FontWeight.Bold, fontSize = 21.sp, color = foreground)
                Text("Dein digitaler Haushalt", fontSize = 11.sp, color = foreground.copy(alpha = .78f))
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Outlined.VerifiedUser, null, tint = foreground.copy(alpha = .75f))
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
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
            Card(onClick = { onOpen(d) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
    onBackup: (String) -> Unit, onRestore: (String) -> Unit,
    onSection: (String) -> Unit, dark: Boolean, onDark: (Boolean) -> Unit,
    widgets: List<String>, onWidgets: (List<String>) -> Unit,
    autoEnabled: Boolean, autoLast: String,
    onAutoBackup: (String) -> Unit, onAutoDisable: () -> Unit
) {
    var passwordDialog by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            Text("Einstellungen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Privat, sicher und ohne Benutzerkonto", color = muted)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
            Text("Verwaltungsbereiche", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                V3Sections.all.forEach { section ->
                    OutlinedCard(onClick = { onSection(section) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(section, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                        }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DarkMode, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("Dunkles Design", modifier = Modifier.weight(1f))
                Switch(checked = dark, onCheckedChange = onDark)
            }
        }
        item { HomeWidgets.Editor(widgets, onWidgets) }
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
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(15.dp)) {
                    Text("Automatische verschlüsselte Sicherung", fontWeight = FontWeight.SemiBold)
                    Text("Täglich im selbst ausgewählten Ordner, optional auch über einen Cloud-Dateianbieter.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(autoLast, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    OutlinedButton(onClick = {
                        if (autoEnabled) onAutoDisable()
                        else { password = ""; passwordDialog = "auto" }
                    }) { Text(if (autoEnabled) "Automatisches Backup deaktivieren" else "Automatische Sicherung einrichten") }
                    Text("Das Passwort wird geschützt auf dem Gerät hinterlegt. Für Gerätewechsel Passwort separat aufbewahren.",
                        fontSize = 11.sp)
                }
            }
        }
        item {
            Text("Archivator v2.5 · Offline-first", fontSize = 12.sp, color = muted)
            Text("Der ML-Kit-Scanner benötigt Google Play-Dienste; die Texterkennung läuft lokal.",
                color = muted, fontSize = 12.sp)
        }
    }
    if (passwordDialog != null) {
        val kind = passwordDialog
        AlertDialog(
            onDismissRequest = { passwordDialog = null },
            title = { Text(if (kind == "backup") "Backup-Passwort festlegen"
                else if (kind == "auto") "Passwort für automatische Backups"
                else "Backup entschlüsseln") },
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
                    when (kind) {
                        "backup" -> onBackup(password)
                        "auto" -> onAutoBackup(password)
                        else -> onRestore(password)
                    }
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
