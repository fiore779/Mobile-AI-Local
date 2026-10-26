package com.mobileailocal

import com.mobileailocal.data.MIGRATION_1_2
import com.mobileailocal.data.MIGRATION_1_2_STATEMENTS
import com.mobileailocal.data.MIGRATION_2_3
import com.mobileailocal.data.MIGRATION_2_3_STATEMENTS
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Room が出力したスキーマ(app/schemas)と、手書きの Migration(1, 2) の SQL が合っているかを確かめる。
 * 実際の SQLite での実行は JVM では行えないので、更新インストールでの確認(設計書 14 章 R14)は別途必要。
 */
class MigrationSchemaTest {
    private fun schema(v: Int): JSONObject {
        val f = File("schemas/com.mobileailocal.data.AppDatabase/$v.json")
        assertTrue("スキーマが無い: ${f.absolutePath}", f.exists())
        return JSONObject(f.readText()).getJSONObject("database")
    }

    private fun entities(db: JSONObject): Map<String, JSONObject> {
        val a = db.getJSONArray("entities")
        return (0 until a.length()).associate { a.getJSONObject(it).getString("tableName") to a.getJSONObject(it) }
    }

    private fun norm(sql: String, table: String) =
        sql.replace("`\${TABLE_NAME}`", table).replace("\${TABLE_NAME}", table).replace("`", "")
            .replace(Regex("\\s+"), " ").replace("( ", "(").replace(" )", ")").trim()

    private fun JSONArray.objs() = (0 until length()).map { getJSONObject(it) }

    @Test fun versionsAreOneAndTwo() {
        assertEquals(1, schema(1).getInt("version"))
        assertEquals(2, schema(2).getInt("version"))
        assertEquals(1, MIGRATION_1_2.startVersion)
        assertEquals(2, MIGRATION_1_2.endVersion)
    }

    @Test fun onlyCloudIntegrationIsNewAndOthersUnchanged() {
        val v1 = entities(schema(1))
        val v2 = entities(schema(2))
        assertEquals(setOf("cloud_integration"), v2.keys - v1.keys)
        assertEquals(emptySet<String>(), v1.keys - v2.keys)
        for (t in listOf("conversation", "model")) {
            assertEquals(t, v1.getValue(t).getString("createSql"), v2.getValue(t).getString("createSql"))
        }
    }

    @Test fun newTableSqlMatchesRoomSchema() {
        val expected = norm(entities(schema(2)).getValue("cloud_integration").getString("createSql"), "cloud_integration")
        val actual = MIGRATION_1_2_STATEMENTS.map { norm(it, "") }.firstOrNull { it.startsWith("CREATE TABLE IF NOT EXISTS cloud_integration") }
        assertEquals(expected, actual)
    }

    @Test fun messageGainsOnlyOriginColumnWithMatchingDefinition() {
        val f1 = entities(schema(1)).getValue("message").getJSONArray("fields").objs()
        val f2 = entities(schema(2)).getValue("message").getJSONArray("fields").objs()
        // 既存の列は順序・定義とも変わらない
        assertEquals(f1.map { it.toString() }, f2.take(f1.size).map { it.toString() })
        val added = f2.drop(f1.size)
        assertEquals(listOf("origin"), added.map { it.getString("columnName") })
        val o = added.single()
        assertTrue(o.getBoolean("notNull"))
        assertEquals("TEXT", o.getString("affinity"))
        val alter = MIGRATION_1_2_STATEMENTS.first { it.startsWith("ALTER TABLE message") }
        assertEquals("ALTER TABLE message ADD COLUMN origin TEXT NOT NULL DEFAULT ${o.getString("defaultValue")}", alter)
        // message の外部キー・索引は変えていない
        val m1 = entities(schema(1)).getValue("message")
        val m2 = entities(schema(2)).getValue("message")
        assertEquals(m1.getJSONArray("indices").toString(), m2.getJSONArray("indices").toString())
        assertEquals(m1.getJSONArray("foreignKeys").toString(), m2.getJSONArray("foreignKeys").toString())
    }

    @Test fun migrationNeverDropsOrDeletes() {
        for (s in MIGRATION_1_2_STATEMENTS) {
            val u = s.uppercase()
            assertFalse(s, u.contains("DROP ") || u.contains("DELETE "))
        }
    }

    @Test fun databaseUsesNoDestructiveFallback() {
        val src = File("src/main/java/com/mobileailocal").walkTopDown().filter { it.extension == "kt" }
            .joinToString("\n") { f -> f.readLines().filterNot { it.trim().startsWith("//") }.joinToString("\n").let { t ->
                // コメント内の言及は除く
                t.lines().map { l -> l.substringBefore("//") }.joinToString("\n")
            } }
        assertFalse(src.contains("fallbackToDestructiveMigration"))
    }

    // ---- 2 → 3(会話の要約の列) ----

    @Test fun v3VersionsAndMigrationRange() {
        assertEquals(3, schema(3).getInt("version"))
        assertEquals(2, MIGRATION_2_3.startVersion)
        assertEquals(3, MIGRATION_2_3.endVersion)
    }

    @Test fun conversationGainsOnlySummaryColumnsMatchingSql() {
        val f2 = entities(schema(2)).getValue("conversation").getJSONArray("fields").objs()
        val f3 = entities(schema(3)).getValue("conversation").getJSONArray("fields").objs()
        assertEquals(f2.map { it.toString() }, f3.take(f2.size).map { it.toString() })
        val added = f3.drop(f2.size)
        assertEquals(listOf("summaryText", "summaryCount"), added.map { it.getString("columnName") })
        for (col in added) {
            assertTrue(col.getBoolean("notNull"))
            val sql = MIGRATION_2_3_STATEMENTS.first { it.contains("ADD COLUMN ${col.getString("columnName")} ") }
            assertEquals(
                "ALTER TABLE conversation ADD COLUMN ${col.getString("columnName")} ${col.getString("affinity")} NOT NULL DEFAULT ${col.getString("defaultValue")}",
                sql,
            )
        }
    }

    @Test fun otherTablesUnchangedIn3AndNoDestructiveSql() {
        val v2 = entities(schema(2))
        val v3 = entities(schema(3))
        assertEquals(v2.keys, v3.keys)
        for (t in v2.keys - "conversation") assertEquals(t, v2.getValue(t).toString(), v3.getValue(t).toString())
        for (s in MIGRATION_2_3_STATEMENTS) {
            val u = s.uppercase()
            assertFalse(s, u.contains("DROP ") || u.contains("DELETE "))
        }
    }
}
