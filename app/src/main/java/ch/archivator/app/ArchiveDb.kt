package ch.archivator.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

data class ArchiveDoc(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Neues Dokument",
    val category: String = "Sonstiges",
    val person: String = "",
    val tags: String = "",
    val ocr: String = "",
    val merchant: String = "",
    val amount: String = "",
    val due: String = "",
    val purchased: String = "",
    val warrantyMonths: Int = 0,
    val status: String = "Offen",
    val note: String = "",
    val file: String = "",
    val mime: String = "application/pdf",
    val sha256: String = "",
    val created: Long = System.currentTimeMillis(),
    val iban: String = "",
    val reference: String = "",
    val invoiceNumber: String = "",
    val recurringMonths: Int = 0,
    val reimbursed: String = "",
    val deductible: String = "",
    val qrPayload: String = ""
)

val categories = listOf("Alle", "Rechnung", "Quittung", "Garantie", "Krankenkasse", "Vertrag", "Steuern", "Sonstiges")
val states = listOf("Offen", "Bezahlt", "Eingereicht", "In Bearbeitung", "Teilweise erstattet", "Erledigt")
val categoryIcons = mapOf("Rechnung" to "🧾", "Quittung" to "🛍", "Garantie" to "🛡", "Krankenkasse" to "♡", "Vertrag" to "✍", "Steuern" to "📁", "Sonstiges" to "📄")

class ArchiveDb(context: Context) : SQLiteOpenHelper(context, "archivator.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE docs(
                id TEXT PRIMARY KEY, title TEXT NOT NULL, category TEXT NOT NULL,
                person TEXT NOT NULL, tags TEXT NOT NULL, ocr TEXT NOT NULL,
                merchant TEXT NOT NULL, amount TEXT NOT NULL, due TEXT NOT NULL,
                purchased TEXT NOT NULL, warrantyMonths INTEGER NOT NULL,
                status TEXT NOT NULL, note TEXT NOT NULL, file TEXT NOT NULL,
                mime TEXT NOT NULL, sha256 TEXT NOT NULL, created INTEGER NOT NULL,
                iban TEXT NOT NULL DEFAULT '', reference TEXT NOT NULL DEFAULT '',
                invoiceNumber TEXT NOT NULL DEFAULT '', recurringMonths INTEGER NOT NULL DEFAULT 0,
                reimbursed TEXT NOT NULL DEFAULT '', deductible TEXT NOT NULL DEFAULT '',
                qrPayload TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_docs_due ON docs(due)")
        db.execSQL("CREATE INDEX idx_docs_sha ON docs(sha256)")
        db.execSQL("CREATE INDEX idx_docs_category ON docs(category)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            listOf(
                "iban TEXT NOT NULL DEFAULT ''",
                "reference TEXT NOT NULL DEFAULT ''",
                "invoiceNumber TEXT NOT NULL DEFAULT ''",
                "recurringMonths INTEGER NOT NULL DEFAULT 0",
                "reimbursed TEXT NOT NULL DEFAULT ''",
                "deductible TEXT NOT NULL DEFAULT ''",
                "qrPayload TEXT NOT NULL DEFAULT ''"
            ).forEach { db.execSQL("ALTER TABLE docs ADD COLUMN $it") }
        }
    }

    fun put(d: ArchiveDoc) {
        val v = ContentValues().apply {
            put("id", d.id); put("title", d.title); put("category", d.category)
            put("person", d.person); put("tags", d.tags); put("ocr", d.ocr)
            put("merchant", d.merchant); put("amount", d.amount); put("due", d.due)
            put("purchased", d.purchased); put("warrantyMonths", d.warrantyMonths)
            put("status", d.status); put("note", d.note); put("file", d.file)
            put("mime", d.mime); put("sha256", d.sha256); put("created", d.created)
            put("iban", d.iban); put("reference", d.reference)
            put("invoiceNumber", d.invoiceNumber); put("recurringMonths", d.recurringMonths)
            put("reimbursed", d.reimbursed); put("deductible", d.deductible); put("qrPayload", d.qrPayload)
        }
        writableDatabase.insertWithOnConflict("docs", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun all(): List<ArchiveDoc> {
        val result = mutableListOf<ArchiveDoc>()
        readableDatabase.rawQuery("SELECT * FROM docs ORDER BY created DESC", null).use { c ->
            while (c.moveToNext()) result += c.toDoc()
        }
        return result
    }

    fun byId(id: String): ArchiveDoc? =
        readableDatabase.query("docs", null, "id=?", arrayOf(id), null, null, null).use {
            if (it.moveToFirst()) it.toDoc() else null
        }

    fun byHash(hash: String): ArchiveDoc? {
        if (hash.isEmpty()) return null
        return readableDatabase.query("docs", null, "sha256=?", arrayOf(hash), null, null, null, "1").use {
            if (it.moveToFirst()) it.toDoc() else null
        }
    }

    fun remove(id: String) = writableDatabase.delete("docs", "id=?", arrayOf(id))

    private fun Cursor.toDoc() = ArchiveDoc(
        id = getString(getColumnIndexOrThrow("id")),
        title = getString(getColumnIndexOrThrow("title")),
        category = getString(getColumnIndexOrThrow("category")),
        person = getString(getColumnIndexOrThrow("person")),
        tags = getString(getColumnIndexOrThrow("tags")),
        ocr = getString(getColumnIndexOrThrow("ocr")),
        merchant = getString(getColumnIndexOrThrow("merchant")),
        amount = getString(getColumnIndexOrThrow("amount")),
        due = getString(getColumnIndexOrThrow("due")),
        purchased = getString(getColumnIndexOrThrow("purchased")),
        warrantyMonths = getInt(getColumnIndexOrThrow("warrantyMonths")),
        status = getString(getColumnIndexOrThrow("status")),
        note = getString(getColumnIndexOrThrow("note")),
        file = getString(getColumnIndexOrThrow("file")),
        mime = getString(getColumnIndexOrThrow("mime")),
        sha256 = getString(getColumnIndexOrThrow("sha256")),
        created = getLong(getColumnIndexOrThrow("created")),
        iban = getString(getColumnIndexOrThrow("iban")),
        reference = getString(getColumnIndexOrThrow("reference")),
        invoiceNumber = getString(getColumnIndexOrThrow("invoiceNumber")),
        recurringMonths = getInt(getColumnIndexOrThrow("recurringMonths")),
        reimbursed = getString(getColumnIndexOrThrow("reimbursed")),
        deductible = getString(getColumnIndexOrThrow("deductible")),
        qrPayload = getString(getColumnIndexOrThrow("qrPayload"))
    )
}
