package com.local.deposittracker.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LedgerDatabase::class.java)
    @Test fun upgradePreservesExistingSalaryRulesAndTransactions() {
        helper.createDatabase("migration-test", 1).apply {
            execSQL("INSERT INTO monthly_rules (id,name,amount,dayOfMonth,startDate,endDate,targetAccountId,enabled,lastProcessedDate,createdAt,updatedAt) VALUES ('r','工资结余',300000,15,'2026-07-01',NULL,'a',1,'2026-09-15','x','x')")
            execSQL("INSERT INTO transactions (id,type,amount,date,accountId,relatedDepositId,relatedRuleId,title,note,createdAt) VALUES ('monthly:r:2026-09-15','MONTHLY_DEPOSIT',300000,'2026-09-15','a',NULL,'r','工资结余','','x')")
            close()
        }
        helper.runMigrationsAndValidate("migration-test", 3, true, LedgerDatabase.MIGRATION_1_2, LedgerDatabase.MIGRATION_2_3).apply {
            query("SELECT kind,lastProcessedDate FROM monthly_rules WHERE id='r'").use { assertTrue(it.moveToFirst()); assertEquals("MONTHLY_DEPOSIT", it.getString(0)); assertEquals("2026-09-15", it.getString(1)) }
            query("SELECT amount,transferGroupId,imagesJson FROM transactions").use { assertTrue(it.moveToFirst()); assertEquals(300000L, it.getLong(0)); assertTrue(it.isNull(1)); assertEquals("[]", it.getString(2)) }
            close()
        }
    }
}
