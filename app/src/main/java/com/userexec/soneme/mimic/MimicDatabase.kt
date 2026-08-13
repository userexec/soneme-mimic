package com.userexec.soneme.mimic

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

class MimicDatabase(context: Context) : SQLiteOpenHelper(context, "mimic.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE codes (
                uid TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                name_key TEXT NOT NULL UNIQUE,
                collection TEXT NOT NULL,
                item_kind TEXT NOT NULL DEFAULT 'code',
                format TEXT NOT NULL,
                convention TEXT,
                payload TEXT NOT NULL,
                sort_order INTEGER NOT NULL,
                display_rotation INTEGER NOT NULL DEFAULT -1,
                display_inverted INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_codes_collection_order ON codes(collection, sort_order)")
        createV2Tables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE codes ADD COLUMN item_kind TEXT NOT NULL DEFAULT 'code'")
            createV2Tables(db)
        }
    }

    private fun createV2Tables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS text_fields (
                item_uid TEXT NOT NULL,
                sort_order INTEGER NOT NULL,
                heading TEXT NOT NULL,
                text_value TEXT NOT NULL,
                PRIMARY KEY(item_uid, sort_order)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_text_fields_item ON text_fields(item_uid, sort_order)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS photos (
                id TEXT PRIMARY KEY,
                item_uid TEXT NOT NULL,
                file_name TEXT NOT NULL UNIQUE,
                sort_order INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_item ON photos(item_uid, sort_order)")
    }

    fun list(collection: CollectionKind): List<CodeRecord> {
        val result = mutableListOf<CodeRecord>()
        readableDatabase.query(
            "codes", null, "collection=?", arrayOf(collection.dbValue),
            null, null, "sort_order ASC"
        ).use { c ->
            while (c.moveToNext()) result += fromCursor(c)
        }
        return result
    }

    fun get(uid: String): CodeRecord? =
        readableDatabase.query("codes", null, "uid=?", arrayOf(uid), null, null, null).use { c ->
            if (c.moveToFirst()) fromCursor(c) else null
        }

    fun textFields(uid: String): List<TextFieldRecord> {
        val result = mutableListOf<TextFieldRecord>()
        readableDatabase.query(
            "text_fields", null, "item_uid=?", arrayOf(uid),
            null, null, "sort_order ASC"
        ).use { c ->
            while (c.moveToNext()) {
                result += TextFieldRecord(
                    heading = c.getString(c.getColumnIndexOrThrow("heading")),
                    text = c.getString(c.getColumnIndexOrThrow("text_value")),
                    sortOrder = c.getInt(c.getColumnIndexOrThrow("sort_order"))
                )
            }
        }
        return result
    }

    fun photos(uid: String): List<PhotoRecord> {
        val result = mutableListOf<PhotoRecord>()
        readableDatabase.query(
            "photos", null, "item_uid=?", arrayOf(uid),
            null, null, "sort_order ASC"
        ).use { c ->
            while (c.moveToNext()) {
                result += PhotoRecord(
                    id = c.getString(c.getColumnIndexOrThrow("id")),
                    itemUid = c.getString(c.getColumnIndexOrThrow("item_uid")),
                    fileName = c.getString(c.getColumnIndexOrThrow("file_name")),
                    sortOrder = c.getInt(c.getColumnIndexOrThrow("sort_order"))
                )
            }
        }
        return result
    }

    fun isNameAvailable(name: String, excludingUid: String?): Boolean {
        val key = normalizedName(name)
        val args = if (excludingUid == null) arrayOf(key) else arrayOf(key, excludingUid)
        val where = if (excludingUid == null) "name_key=?" else "name_key=? AND uid<>?"
        return readableDatabase.rawQuery("SELECT 1 FROM codes WHERE $where LIMIT 1", args).use { !it.moveToFirst() }
    }

    fun save(record: CodeRecord, textFields: List<TextFieldRecord> = emptyList()): CodeRecord {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = queryRecord(db, record.uid)
            val moving = old != null && old.collection != record.collection
            val order = when {
                old == null -> nextOrder(db, record.collection)
                moving -> nextOrder(db, record.collection)
                else -> old.sortOrder
            }
            val v = ContentValues().apply {
                put("uid", record.uid)
                put("name", record.name)
                put("name_key", normalizedName(record.name))
                put("collection", record.collection.dbValue)
                put("item_kind", record.kind.dbValue)
                put("format", record.format.name)
                if (record.convention == null) putNull("convention") else put("convention", record.convention.dbValue)
                put("payload", record.payload)
                put("sort_order", order)
                put("display_rotation", record.displayRotation)
                put("display_inverted", if (record.displayInverted) 1 else 0)
            }
            if (old == null) {
                db.insertOrThrow("codes", null, v)
            } else {
                val changed = db.update("codes", v, "uid=?", arrayOf(record.uid))
                check(changed == 1) { "Item record disappeared during update" }
            }

            db.delete("text_fields", "item_uid=?", arrayOf(record.uid))
            if (record.kind == ItemKind.PLAIN_TEXT) {
                textFields.forEachIndexed { index, field ->
                    db.insertOrThrow("text_fields", null, ContentValues().apply {
                        put("item_uid", record.uid)
                        put("sort_order", index)
                        put("heading", field.heading)
                        put("text_value", field.text)
                    })
                }
            }

            if (moving && old != null) compact(db, old.collection)
            db.setTransactionSuccessful()
            return record.copy(sortOrder = order)
        } finally {
            db.endTransaction()
        }
    }

    fun replacePhotos(uid: String, photos: List<PhotoRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("photos", "item_uid=?", arrayOf(uid))
            photos.forEachIndexed { index, photo ->
                db.insertOrThrow("photos", null, ContentValues().apply {
                    put("id", photo.id)
                    put("item_uid", uid)
                    put("file_name", photo.fileName)
                    put("sort_order", index)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun delete(uid: String) {
        val old = get(uid) ?: return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("text_fields", "item_uid=?", arrayOf(uid))
            db.delete("photos", "item_uid=?", arrayOf(uid))
            db.delete("codes", "uid=?", arrayOf(uid))
            compact(db, old.collection)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun moveUp(uid: String) {
        val item = get(uid) ?: return
        if (item.sortOrder <= 0) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previousUid = db.rawQuery(
                "SELECT uid FROM codes WHERE collection=? AND sort_order<? ORDER BY sort_order DESC LIMIT 1",
                arrayOf(item.collection.dbValue, item.sortOrder.toString())
            ).use { if (it.moveToFirst()) it.getString(0) else null } ?: return
            val previous = queryRecord(db, previousUid) ?: return
            db.execSQL("UPDATE codes SET sort_order=? WHERE uid=?", arrayOf(previous.sortOrder, item.uid))
            db.execSQL("UPDATE codes SET sort_order=? WHERE uid=?", arrayOf(item.sortOrder, previous.uid))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateDisplay(uid: String, rotation: Int) {
        val v = ContentValues().apply { put("display_rotation", rotation) }
        writableDatabase.update("codes", v, "uid=?", arrayOf(uid))
    }

    private fun nextOrder(db: SQLiteDatabase, collection: CollectionKind): Int =
        db.rawQuery("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM codes WHERE collection=?", arrayOf(collection.dbValue))
            .use { it.moveToFirst(); it.getInt(0) }

    private fun compact(db: SQLiteDatabase, collection: CollectionKind) {
        val ids = mutableListOf<String>()
        db.rawQuery("SELECT uid FROM codes WHERE collection=? ORDER BY sort_order", arrayOf(collection.dbValue)).use { c ->
            while (c.moveToNext()) ids += c.getString(0)
        }
        ids.forEachIndexed { index, uid ->
            db.execSQL("UPDATE codes SET sort_order=? WHERE uid=?", arrayOf(index, uid))
        }
    }

    private fun normalizedName(name: String) = name.lowercase(Locale.ROOT)

    private fun queryRecord(db: SQLiteDatabase, uid: String): CodeRecord? =
        db.query("codes", null, "uid=?", arrayOf(uid), null, null, null).use { c ->
            if (c.moveToFirst()) fromCursor(c) else null
        }

    private fun fromCursor(c: android.database.Cursor) = CodeRecord(
        uid = c.getString(c.getColumnIndexOrThrow("uid")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        collection = CollectionKind.fromDb(c.getString(c.getColumnIndexOrThrow("collection"))),
        kind = ItemKind.fromDb(c.getString(c.getColumnIndexOrThrow("item_kind"))),
        format = Formats.fromName(c.getString(c.getColumnIndexOrThrow("format"))),
        convention = c.getColumnIndexOrThrow("convention").let { if (c.isNull(it)) null else QrConvention.fromDb(c.getString(it)) },
        payload = c.getString(c.getColumnIndexOrThrow("payload")),
        sortOrder = c.getInt(c.getColumnIndexOrThrow("sort_order")),
        displayRotation = c.getInt(c.getColumnIndexOrThrow("display_rotation")),
        displayInverted = c.getInt(c.getColumnIndexOrThrow("display_inverted")) != 0
    )
}
