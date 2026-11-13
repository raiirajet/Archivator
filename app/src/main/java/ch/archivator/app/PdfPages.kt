package ch.archivator.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.net.Uri
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.graphics.graphicsLayer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PdfPageOrder(val originalIndex: Int, val rotation: Int = 0)

object PdfPages {
    fun pageCount(ctx: Context, doc: ArchiveDoc): Int {
        if (!doc.mime.contains("pdf")) return 1
        val f = Vault.plaintext(ctx, doc)
        try {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { return it.pageCount }
            }
        } finally { if (Vault.isEncrypted(doc)) f.delete() }
    }

    fun pageBitmap(ctx: Context, doc: ArchiveDoc, index: Int, rotation: Int = 0): Bitmap {
        val f = Vault.plaintext(ctx, doc)
        try {
            val raw: Bitmap = if (doc.mime.contains("pdf")) {
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        renderer.openPage(index).use { page ->
                            val scale = minOf(2.2f, 1600f / maxOf(page.width, page.height))
                            Bitmap.createBitmap(
                                maxOf(1, (page.width * scale).toInt()),
                                maxOf(1, (page.height * scale).toInt()),
                                Bitmap.Config.ARGB_8888).apply {
                                eraseColor(AndroidColor.WHITE)
                                page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            } else {
                BitmapFactory.decodeFile(f.absolutePath) ?: error("Bild kann nicht dargestellt werden")
            }
            return if (rotation % 360 == 0) raw else {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also {
                    if (it !== raw) raw.recycle()
                }
            }
        } finally { if (Vault.isEncrypted(doc)) f.delete() }
    }

    /** Reorder, rotate or delete PDF pages without rasterizing text or vectors. */
    fun rewrite(ctx: Context, db: ArchiveDb, doc: ArchiveDoc, order: List<PdfPageOrder>) {
        require(doc.mime.contains("pdf")) { "Nur PDF-Dokumente unterstützt" }
        require(order.isNotEmpty()) { "Mindestens eine Seite erforderlich" }
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val plain = Vault.plaintext(ctx, doc)
        val output = File(ctx.cacheDir, "edited-${System.nanoTime()}.pdf")
        try {
            PDDocument.load(plain).use { original ->
                require(order.size <= 300) { "Zu viele Seiten" }
                PDDocument().use { target ->
                    for (item in order) {
                        require(item.originalIndex in 0 until original.numberOfPages) { "Ungültiger Seitenindex" }
                        val page = target.importPage(original.getPage(item.originalIndex))
                        page.rotation = (page.rotation + item.rotation) % 360
                    }
                    target.save(output)
                }
            }
            Vault.replace(ctx, db, doc, output)
        } finally {
            output.delete()
            if (Vault.isEncrypted(doc)) plain.delete()
        }
    }

    fun merge(ctx: Context, db: ArchiveDb, primary: ArchiveDoc, other: ArchiveDoc) {
        require(primary.mime.contains("pdf") && other.mime.contains("pdf"))
        require(primary.id != other.id)
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val first = Vault.plaintext(ctx, primary)
        val second = Vault.plaintext(ctx, other)
        val output = File(ctx.cacheDir, "merged-${System.nanoTime()}.pdf")
        try {
            PDDocument.load(first).use { result ->
                PDDocument.load(second).use { imported ->
                    require(result.numberOfPages + imported.numberOfPages <= 300)
                    for (i in 0 until imported.numberOfPages) result.importPage(imported.getPage(i))
                }
                result.save(output)
            }
            Vault.replace(ctx, db, primary, output)
        } finally {
            output.delete()
            if (Vault.isEncrypted(primary)) first.delete()
            if (Vault.isEncrypted(other)) second.delete()
        }
    }

    fun extract(ctx: Context, doc: ArchiveDoc, index: Int, destination: Uri) {
        require(doc.mime.contains("pdf"))
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val plain = Vault.plaintext(ctx, doc)
        val output = File(ctx.cacheDir, "extracted-${System.nanoTime()}.pdf")
        try {
            PDDocument.load(plain).use { original ->
                require(index in 0 until original.numberOfPages)
                PDDocument().use { target ->
                    target.importPage(original.getPage(index))
                    target.save(output)
                }
            }
            ctx.contentResolver.openOutputStream(destination, "w")?.use { out ->
                output.inputStream().use { it.copyTo(out) }
            } ?: error("Ziel konnte nicht geöffnet werden")
        } finally {
            output.delete()
            if (Vault.isEncrypted(doc)) plain.delete()
        }
    }
}

@Composable
fun PdfPreviewScreen(ctx: Context, doc: ArchiveDoc, allDocs: List<ArchiveDoc>,
                     onBack: () -> Unit, onUpdated: () -> Unit,
                     onExtract: (Int) -> Unit) {
    var order by remember(doc.id) { mutableStateOf(listOf(PdfPageOrder(0))) }
    var selected by remember(doc.id) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var showMerge by remember { mutableStateOf(false) }
    var scale by remember(doc.id, selected) { mutableFloatStateOf(1f) }
    var panX by remember(doc.id, selected) { mutableFloatStateOf(0f) }
    var panY by remember(doc.id, selected) { mutableFloatStateOf(0f) }
    val zoomState = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 4f)
        panX += pan.x
        panY += pan.y
    }
    LaunchedEffect(doc.id) {
        val result = withContext(Dispatchers.IO) { runCatching { PdfPages.pageCount(ctx, doc) } }
        result.onSuccess { count ->
            order = (0 until count).map { PdfPageOrder(it) }
            selected = 0
        }.onFailure { error = it.localizedMessage }
        busy = false
    }
    val item = order.getOrNull(selected)
    val image by produceState<Bitmap?>(initialValue = null, doc.id, item) {
        value = null
        if (item != null && !busy) {
            value = withContext(Dispatchers.IO) {
                runCatching { PdfPages.pageBitmap(ctx, doc, item.originalIndex, item.rotation) }.getOrNull()
            }
        }
    }
    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(WindowInsets.statusBars).height(62.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück") }
                Text("Dokumentvorschau", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                if (doc.mime.contains("pdf")) {
                    TextButton(enabled = !busy && error == null, onClick = {
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { PdfPages.rewrite(ctx, ArchiveDb(ctx), doc, order) }
                            }
                            result.onSuccess { onUpdated() }.onFailure { error = it.localizedMessage }
                            busy = false
                        }
                    }) { Text("Speichern") }
                }
            }
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (busy) CircularProgressIndicator()
            error?.let { Text("Fehler: $it", color = MaterialTheme.colorScheme.error) }
            if (item != null) {
                Text("Seite ${selected + 1} von ${order.size}", style = MaterialTheme.typography.titleMedium)
                if (image != null) {
                    Card(Modifier.fillMaxWidth().weight(1f),
                        shape = RoundedCornerShape(16.dp)) {
                        Image(image!!.asImageBitmap(), "Seite ${selected + 1}",
                            Modifier.fillMaxSize().padding(8.dp).transformable(zoomState)
                                .graphicsLayer(scaleX = scale, scaleY = scale,
                                    translationX = panX, translationY = panY),
                            contentScale = ContentScale.Fit)
                    }
                } else Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = selected > 0, onClick = { selected-- }) {
                        Icon(Icons.Outlined.ChevronLeft, "Vorherige Seite")
                    }
                    IconButton(enabled = selected < order.lastIndex, onClick = { selected++ }) {
                        Icon(Icons.Outlined.ChevronRight, "Nächste Seite")
                    }
                    IconButton(enabled = doc.mime.contains("pdf"), onClick = {
                        order = order.toMutableList().also {
                            it[selected] = it[selected].copy(rotation = (it[selected].rotation + 90) % 360)
                        }
                    }) { Icon(Icons.Outlined.RotateRight, "Seite drehen") }
                    IconButton(enabled = doc.mime.contains("pdf") && selected > 0, onClick = {
                        order = order.toMutableList().also { it.add(selected - 1, it.removeAt(selected)) }; selected--
                    }) { Icon(Icons.Outlined.ArrowUpward, "Seite nach vorne") }
                    IconButton(enabled = doc.mime.contains("pdf") && selected < order.lastIndex, onClick = {
                        order = order.toMutableList().also { it.add(selected + 1, it.removeAt(selected)) }; selected++
                    }) { Icon(Icons.Outlined.ArrowDownward, "Seite nach hinten") }
                    IconButton(enabled = doc.mime.contains("pdf") && order.size > 1, onClick = {
                        order = order.toMutableList().also { it.removeAt(selected) }
                        selected = selected.coerceAtMost(order.lastIndex)
                    }) { Icon(Icons.Outlined.DeleteOutline, "Seite entfernen") }
                }
                if (doc.mime.contains("pdf")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { onExtract(item.originalIndex) }) { Text("Seite exportieren") }
                        if (allDocs.any { it.id != doc.id && it.mime.contains("pdf") }) {
                            OutlinedButton(onClick = { showMerge = true }) { Text("PDF zusammenführen") }
                        }
                    }
                    Text("Text und Vektorgrafiken werden beim Sortieren, Drehen und Zusammenführen erhalten. Digitale Signaturen können dabei ungültig werden.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (showMerge) AlertDialog(
        onDismissRequest = { showMerge = false },
        title = { Text("Mit welchem PDF zusammenführen?") },
        text = {
            Column {
                allDocs.filter { it.id != doc.id && it.mime.contains("pdf") }.take(25).forEach { candidate ->
                    TextButton(onClick = {
                        showMerge = false
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { ArchiveDb(ctx).use { PdfPages.merge(ctx, it, doc, candidate) } }
                            }
                            result.onSuccess { onUpdated() }
                                .onFailure { error = "PDF konnte nicht zusammengeführt werden: ${it.message}" }
                            busy = false
                        }
                    }) { Text(candidate.title) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showMerge = false }) { Text("Schliessen") } }
    )
}
