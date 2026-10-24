package com.gridsense.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationsTest {

    private fun schema(version: Int): String? =
        javaClass.classLoader!!
            .getResource("com.gridsense.data.Db/$version.json")
            ?.readText()

    private fun identityHash(json: String): String =
        Regex("\"identityHash\"\\s*:\\s*\"([0-9a-f]+)\"").find(json)!!.groupValues[1]

    /** table -> (column -> notNull), read from an exported schema. */
    private fun columns(json: String): Map<String, Map<String, Boolean>> {
        val chunks = json.split("\"tableName\": \"").drop(1)
        return chunks.associate { chunk ->
            val table = chunk.substringBefore('"')
            val fields = Regex(
                "\"columnName\":\\s*\"(\\w+)\",\\s*\"affinity\":\\s*\"\\w+\",\\s*\"notNull\":\\s*(true|false)"
            ).findAll(chunk).associate { it.groupValues[1] to (it.groupValues[2] == "true") }
            table to fields
        }
    }

    @Test
    fun everyVersionFromFourHasAnExportedSchema() {
        for (v in 4..DB_VERSION) assertNotNull("schema $v.json is missing", schema(v))
    }

    @Test
    fun everyStepFromFourHasAMigration() {
        for (v in 4 until DB_VERSION) {
            val step = ALL_MIGRATIONS.singleOrNull { it.startVersion == v }
            assertNotNull("no migration from $v", step)
            assertEquals(v + 1, step!!.endVersion)
        }
        assertTrue(ALL_MIGRATIONS.all { it.endVersion <= DB_VERSION })
    }

    @Test
    fun fourToFiveLeavesTheSchemaUnchanged() {
        assertEquals(identityHash(schema(4)!!), identityHash(schema(5)!!))
    }

    @Test
    fun everyAddedColumnIsAddedByItsMigrationAndIsNullable() {
        for (v in 4 until DB_VERSION) {
            val before = columns(schema(v)!!)
            val after = columns(schema(v + 1)!!)
            assertEquals("tables changed between $v and ${v + 1}", before.keys, after.keys)
            val sql = ALL_MIGRATIONS.single { it.startVersion == v }.statements
            for ((table, cols) in after) {
                val old = before.getValue(table)
                assertTrue("columns removed from $table in ${v + 1}", old.keys.all { it in cols })
                for ((col, notNull) in cols.filterKeys { it !in old }) {
                    assertTrue(
                        "$table.$col is not added by the $v to ${v + 1} migration",
                        sql.any { it.matches(Regex("ALTER TABLE $table ADD COLUMN $col\\b.*")) }
                    )
                    assertFalse("$table.$col must be nullable or have a default", notNull && sql.none {
                        it.contains(" $col ") && it.contains("DEFAULT")
                    })
                }
            }
            assertEquals(
                "every statement adds a new column",
                after.values.sumOf { it.size } - before.values.sumOf { it.size },
                sql.size
            )
        }
    }

    @Test
    fun fiveToSixAddsTheScanColumns() {
        val sample = columns(schema(6)!!).getValue("sample")
        for (c in listOf("channelWidthMhz", "centerFreq0Mhz", "wifiStandard")) {
            assertEquals(false, sample[c])
        }
    }

    @Test
    fun sixToSevenAddsRouterHeightWithADefault() {
        val router = columns(schema(7)!!).getValue("router")
        assertEquals(true, router["heightDiffM"])
        assertTrue(schema(7)!!.contains("\"defaultValue\": \"0\""))
    }

    @Test
    fun sevenToEightAddsTheConditionWithADefault() {
        assertEquals(true, columns(schema(8)!!).getValue("survey_room")["condition"])
        assertTrue(MIGRATION_7_8.statements.single().endsWith("DEFAULT ''"))
    }
}
