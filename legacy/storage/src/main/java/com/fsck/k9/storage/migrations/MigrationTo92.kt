package com.fsck.k9.storage.migrations

import android.database.sqlite.SQLiteDatabase

internal class MigrationTo92(private val db: SQLiteDatabase) {
    fun addFoldersAggregateTabColumn() {
        db.execSQL("ALTER TABLE folders ADD aggregate_tab INTEGER DEFAULT 0")
    }
}
