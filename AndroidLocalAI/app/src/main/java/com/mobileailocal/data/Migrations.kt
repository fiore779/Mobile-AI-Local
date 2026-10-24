package com.mobileailocal.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 1 → 2 で実行する SQL。テストが同じ文を検証できるよう、リストで公開する。 */
val MIGRATION_1_2_STATEMENTS = listOf(
    "ALTER TABLE message ADD COLUMN origin TEXT NOT NULL DEFAULT 'local'",
    "CREATE TABLE IF NOT EXISTS cloud_integration (uid TEXT NOT NULL, id TEXT NOT NULL, type TEXT NOT NULL, " +
        "displayName TEXT NOT NULL, baseUrl TEXT NOT NULL, enabledModelsJson TEXT NOT NULL, lastModel TEXT, " +
        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, syncState TEXT NOT NULL, PRIMARY KEY(uid, id))",
)

/** message.origin の追加と cloud_integration の新設。既存の会話・メッセージには触れない(origin は 'local')。 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_1_2_STATEMENTS.forEach { db.execSQL(it) }
    }
}
