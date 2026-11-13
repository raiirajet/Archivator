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
    val qrPayload: String = "",
    val groupId: String = "",
    val taxRelevant: Boolean = false,
    val cancellationDate: String = "",
    val policyNumber: String = "",
    val assetName: String = "",
    val paidDate: String = "",
    val reimbursedDate: String = ""
)

val categories = listOf("Alle", "Rechnung", "Quittung", "Garantie", "Krankenkasse",
    "Versicherung", "Vertrag", "Fahrzeug", "Wohnen", "Abonnement",
    "Bedienungsanleitung", "Steuern", "Sonstiges")
val states = listOf("Offen", "Bezahlt", "Eingereicht", "In Bearbeitung",
    "Teilweise erstattet", "Erledigt")
val categoryIcons = mapOf("Rechnung" to "🧾", "Quittung" to "🛍", "Garantie" to "🛡",
    "Krankenkasse" to "♡", "Versicherung" to "🛡", "Vertrag" to "✍",
    "Fahrzeug" to "🚗", "Wohnen" to "🏠", "Abonnement" to "🔁",
    "Bedienungsanleitung" to "📘", "Steuern" to "📁", "Sonstiges" to "📄")

class ArchiveDb(context: Context) : SQLiteOpenHelper(context, "archivator.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE docs (
                id TEXT PRIMARY KEY, title TEXT NOT NULL, category TEXT NOT NULL,
                person TEXT NOT NULL, tags TEXT NOT NULL, ocr TEXT NOT NULL,
                merchant TEXT NOT NULL, amount TEXT NOT NULL, due TEXT NOT NULL,
                purchased TEXT NOT NULL, warrantyMonths INTEGER NOT NULL,
                status TEXT NOT NULL, note TEXT NOT NULL, file TEXT NOT NULL,
                mime TEXT NOT NULL, sha256 TEXT NOT NULL, created INTEGER NOT NULL,
                iban TEXT NOT NULL DEFAULT '', reference TEXT NOT NULL DEFAULT '',
                invoiceNumber TEXT NOT NULL DEFAULT '', recurringMonths INTEGER NOT NULL DEFAULT 0,
                reimbursed TEXT NOT NULL DEFAULT '', deductible TEXT NOT NULL DEFAULT '',
                qrPayload TEXT NOT NULL DEFAULT '', groupId TEXT NOT NULL DEFAULT '',
                taxRelevant INTEGER NOT NULL DEFAULT 0, cancellationDate TEXT NOT NULL DEFAULT '',
                policyNumber TEXT NOT NULL DEFAULT '', assetName TEXT NOT NULL DEFAULT '',
                paidDate TEXT NOT NULL DEFAULT '', reimbursedDate TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        createIndices(db)
        createSearch(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            listOf(
                "iban TEXT NOT NULL DEFAULT ''", "reference TEXT NOT NULL DEFAULT ''",
                "invoiceNumber TEXT NOT NULL DEFAULT ''", "recurringMonths INTEGER NOT NULL DEFAULT 0",
                "reimbursed TEXT NOT NULL DEFAULT ''", "deductible TEXT NOT NULL DEFAULT ''",
                "qrPayload TEXT NOT NULL DEFAULT ''"
            ).forEach { db.execSQL("ALTER TABLE docs ADD COLUMN $it") }
        }
        if (oldVersion < 3) {
            listOf(
                "groupId TEXT NOT NULL DEFAULT ''", "taxRelevant INTEGER NOT NULL DEFAULT 0",
                "cancellationDate TEXT NOT NULL DEFAULT ''", "policyNumber TEXT NOT NULL DEFAULT ''",
                "assetName TEXT NOT NULL DEFAULT ''", "paidDate TEXT NOT NULL DEFAULT ''",
                "reimbursedDate TEXT NOT NULL DEFAULT ''"
            ).forEach { db.execSQL("ALTER TABLE docs ADD COLUMN $it") }
            createIndices(db)
            createSearch(db)
            db.rawQuery("SELECT * FROM docs", null).use { c ->
                while (c.moveToNext()) indexDoc(db, c.toDoc())
            }
        }
    }

    private fun createIndices(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_docs_due ON docs(due)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_docs_sha ON docs(sha256)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_docs_category ON docs(category)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_docs_person ON docs(person)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_docs_group ON docs(groupId)")
    }

    private fun createSearch(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE VIRTUAL TABLE IF NOT EXISTS docs_search USING fts4(
                id, title, category, person, tags, ocr,
                merchant, amount, note, iban, reference,
                invoiceNumber, tokenize=unicode61
            )
        """.trimIndent())
    }

    private fun indexDoc(db: SQLiteDatabase, d: ArchiveDoc) {
        db.delete("docs_search", "id=?", arrayOf(d.id))
        val values = ContentValues().apply {
            put("id", d.id); put("title", d.title); put("category", d.category)
            put("person", d.person); put("tags", d.tags); put("ocr", d.ocr)
            put("merchant", d.merchant); put("amount", d.amount); put("note", d.note)
            put("iban", d.iban); put("reference", d.reference)
            put("invoiceNumber", d.invoiceNumber)
        }
        db.insertOrThrow("docs_search", null, values)
    }

    /** Indexed offline full-text search. Wildcards only on normalized word tokens. */
    fun searchIds(query: String): Set<String> {
        val tokens = Regex("[\\p{L}\\p{N}]+").findAll(query).map { it.value.lowercase() }
            .filter { it.length >= 2 }.take(12).toList()
        if (tokens.isEmpty()) return all().map { it.id }.toSet()
        val match = tokens.joinToString(" AND ") { it + "*" }
        return runCatching {
            val ids = mutableSetOf<String>()
            readableDatabase.rawQuery(
                "SELECT id FROM docs_search WHERE docs_search MATCH ? LIMIT 5000",
                arrayOf(match)
            ).use { c -> while (c.moveToNext()) ids += c.getString(0) }
            ids
        }.getOrElse { emptySet() }
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
            put("reimbursed", d.reimbursed); put("deductible", d.deductible)
            put("qrPayload", d.qrPayload); put("groupId", d.groupId)
            put("taxRelevant", if (d.taxRelevant) 1 else 0)
            put("cancellationDate", d.cancellationDate); put("policyNumber", d.policyNumber)
            put("assetName", d.assetName); put("paidDate", d.paidDate)
            put("reimbursedDate", d.reimbursedDate)
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("docs", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            indexDoc(db, d)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
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

    fun remove(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("docs_search", "id=?", arrayOf(id))
            db.delete("docs", "id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

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
        qrPayload = getString(getColumnIndexOrThrow("qrPayload")),
        groupId = getString(getColumnIndexOrThrow("groupId")),
        taxRelevant = getInt(getColumnIndexOrThrow("taxRelevant")) != 0,
        cancellationDate = getString(getColumnIndexOrThrow("cancellationDate")),
        policyNumber = getString(getColumnIndexOrThrow("policyNumber")),
        assetName = getString(getColumnIndexOrThrow("assetName")),
        paidDate = getString(getColumnIndexOrThrow("paidDate")),
        reimbursedDate = getString(getColumnIndexOrThrow("reimbursedDate"))
    )
}
