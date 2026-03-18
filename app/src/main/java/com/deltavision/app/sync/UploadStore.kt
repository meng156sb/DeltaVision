package com.deltavision.app.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class UploadStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE queue_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                frame_hash TEXT UNIQUE NOT NULL,
                frame_path TEXT NOT NULL,
                metadata_json TEXT NOT NULL,
                detections_json TEXT NOT NULL,
                review_status TEXT NOT NULL,
                state TEXT NOT NULL,
                retry_count INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun enqueue(frameHash: String, framePath: String, metadataJson: String, detectionsJson: String, reviewStatus: String) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("frame_hash", frameHash)
            put("frame_path", framePath)
            put("metadata_json", metadataJson)
            put("detections_json", detectionsJson)
            put("review_status", reviewStatus)
            put("state", "PENDING")
            put("created_at", now)
            put("updated_at", now)
        }
        writableDatabase.insertWithOnConflict("queue_items", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        prune(MAX_RECORDS)
    }

    fun listPending(limit: Int = 20): List<UploadRecord> {
        val cursor = readableDatabase.query(
            "queue_items",
            null,
            "state=?",
            arrayOf("PENDING"),
            null,
            null,
            "created_at ASC",
            limit.toString(),
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        UploadRecord(
                            id = it.getLong(it.getColumnIndexOrThrow("id")),
                            frameHash = it.getString(it.getColumnIndexOrThrow("frame_hash")),
                            framePath = it.getString(it.getColumnIndexOrThrow("frame_path")),
                            metadataJson = it.getString(it.getColumnIndexOrThrow("metadata_json")),
                            detectionsJson = it.getString(it.getColumnIndexOrThrow("detections_json")),
                            reviewStatus = it.getString(it.getColumnIndexOrThrow("review_status")),
                        ),
                    )
                }
            }
        }
    }

    fun markSynced(id: Long) {
        val values = ContentValues().apply {
            put("state", "SYNCED")
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("queue_items", values, "id=?", arrayOf(id.toString()))
    }

    fun markFailed(id: Long) {
        writableDatabase.execSQL(
            "UPDATE queue_items SET retry_count = retry_count + 1, updated_at = ? WHERE id = ?",
            arrayOf(System.currentTimeMillis(), id),
        )
    }

    private fun prune(maxRecords: Int) {
        writableDatabase.execSQL(
            "DELETE FROM queue_items WHERE id IN (SELECT id FROM queue_items ORDER BY updated_at DESC LIMIT -1 OFFSET ?)",
            arrayOf(maxRecords),
        )
    }

    companion object {
        private const val DB_NAME = "upload_queue.db"
        private const val DB_VERSION = 1
        private const val MAX_RECORDS = 2000
    }
}

data class UploadRecord(
    val id: Long,
    val frameHash: String,
    val framePath: String,
    val metadataJson: String,
    val detectionsJson: String,
    val reviewStatus: String,
)
