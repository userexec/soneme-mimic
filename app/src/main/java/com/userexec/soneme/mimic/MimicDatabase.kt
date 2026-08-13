package com.userexec.soneme.mimic

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

class MimicDatabase(context: Context) : SQLiteOpenHelper(context, "mimic.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE codes (
                uid TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                name_key TEXT NOT NULL UNIQUE,
                collection TEXT NOT NULL,
                format TEXT NOT NULL,
                convention TEXT,
                payload TEXT NOT NULL,
                sort_order INTEGER NOT NULL,
                display_rotation INTEGER NOT NULL DEFAULT -1,
                display_inverted INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_codes_collection_order ON codes(collection, sort_order)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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

    fun isNameAvailable(name: String, excludingUid: String?): Boolean {
        val key = normalizedName(name)
        val args = if (excludingUid == null) arrayOf(key) else arrayOf(key, excludingUid)
        val where = if (excludingUid == null) "name_key=?" else "name_key=? AND uid<>?"
        return readableDatabase.rawQuery("SELECT 1 FROM codes WHERE $where LIMIT 1", args).use { !it.moveToFirst() }
    }

    fun save(record: CodeRecord): CodeRecord {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = get(record.uid)
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
                check(changed == 1) { "Code record disappeared during update" }
            }
            if (moving && old != null) compact(db, old.collection)
            db.setTransactionSuccessful()
            return record.copy(sortOrder = order)
        } finally {
            db.endTransaction()
        }
    }

    fun delete(uid: String) {
        val old = get(uid) ?: return
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("codes", "uid=?", arrayOf(uid))
            compact(writableDatabase, old.collection)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
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
            val previous = get(previousUid) ?: return
            db.execSQL("UPDATE codes SET sort_order=? WHERE uid=?", arrayOf(previous.sortOrder, item.uid))
            db.execSQL("UPDATE codes SET sort_order=? WHERE uid=?", arrayOf(item.sortOrder, previous.uid))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateDisplay(uid: String, rotation: Int, inverted: Boolean) {
        val v = ContentValues().apply {
            put("display_rotation", rotation)
            put("display_inverted", if (inverted) 1 else 0)
        }
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

    private fun fromCursor(c: android.database.Cursor) = CodeRecord(
        uid = c.getString(c.getColumnIndexOrThrow("uid")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        collection = CollectionKind.fromDb(c.getString(c.getColumnIndexOrThrow("collection"))),
        format = Formats.fromName(c.getString(c.getColumnIndexOrThrow("format"))),
        convention = c.getColumnIndexOrThrow("convention").let { if (c.isNull(it)) null else QrConvention.fromDb(c.getString(it)) },
        payload = c.getString(c.getColumnIndexOrThrow("payload")),
        sortOrder = c.getInt(c.getColumnIndexOrThrow("sort_order")),
        displayRotation = c.getInt(c.getColumnIndexOrThrow("display_rotation")),
        displayInverted = c.getInt(c.getColumnIndexOrThrow("display_inverted")) != 0
    )
}
