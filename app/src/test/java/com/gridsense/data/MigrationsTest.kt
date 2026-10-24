package com.gridsense.data

import org.junit.Assert.assertEquals
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
}
