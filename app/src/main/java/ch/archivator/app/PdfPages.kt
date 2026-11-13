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

    /**
     * Reorders/rotates pages and stores an encrypted replacement.
     * Android PdfDocument rasterizes these pages (existing PDF text/vector layers
     * become images); a backup is recommended before changing an important PDF.
     */
    fun rewrite(ctx: Context, db: ArchiveDb, doc: ArchiveDoc, order: List<PdfPageOrder>) {
        require(doc.mime.contains("pdf")) { "Nur PDFs können sortiert werden" }
        require(order.isNotEmpty()) { "Mindestens eine Seite muss erhalten bleiben" }
        val output = File(ctx.cacheDir, "edited-${System.nanoTime()}.pdf")
        try {
            val pdf = PdfDocument()
            try {
                for ((position, source) in order.withIndex()) {
                    val bitmap = pageBitmap(ctx, doc, source.originalIndex, source.rotation)
                    try {
                        val width = bitmap.width
                        val height = bitmap.height
                        val info = PdfDocument.PageInfo.Builder(width, height, position + 1).create()
                        val page = pdf.startPage(info)
                        page.canvas.drawColor(AndroidColor.WHITE)
                        page.canvas.drawBitmap(bitmap, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), null)
                        pdf.finishPage(page)
                    } finally { bitmap.recycle() }
                }
                output.outputStream().use { pdf.writeTo(it) }
            } finally { pdf.close() }
            Vault.replace(ctx, db, doc, output)
        } finally { output.delete() }
    }
}

@Composable
fun PdfPreviewScreen(ctx: Context, doc: ArchiveDoc, onBack: () -> Unit, onUpdated: () -> Unit) {
    var order by remember(doc.id) { mutableStateOf(listOf(PdfPageOrder(0))) }
    var selected by remember(doc.id) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
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
            Row(Modifier.fillMaxWidth().background(Color(0xFFACD8F6))
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
                            Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
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
                }
                if (doc.mime.contains("pdf")) {
                    Text("PDF-Seiten können gedreht und umsortiert werden. Beim Speichern wird das PDF neu gerendert; durchsuchbarer PDF-Text kann dabei verloren gehen.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
