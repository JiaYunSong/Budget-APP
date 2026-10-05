package com.local.deposittracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.local.deposittracker.core.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    @Test fun onDiskDatabaseSurvivesReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "persistence-verification.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
        try {
            val repo = LedgerRepository(db)
            repo.update { Engine.createAccount(it, "重启测试", 123456, true, "精确到分") }
            val before = repo.snapshot(); db.close()
            db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
            assertEquals(before, LedgerRepository(db).snapshot())
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun atomicUpdatesRollbackAndSnapshotRoundTrip() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).build()
        try {
            val repo = LedgerRepository(db)
            repo.update { Engine.createAccount(it, "工商银行", 10_000_000, true, "本地持久化测试") }
            val before = repo.snapshot()
            try { repo.update { Engine.createDeposit(it, Deposit(name = "余额不足", principal = 20_000_000, annualRateText = "2", startDate = today(), endDate = LocalDate.now().plusYears(1).toString(), sourceAccountId = before.accounts.single().id, targetAccountId = before.accounts.single().id), allowNegative = false) }; fail("Expected failure") } catch (_: IllegalArgumentException) {}
            assertEquals(before, repo.snapshot())
            val restored = BackupCodec.parse(BackupCodec.export(repo.snapshot())).ledger()
            repo.update { restored }
            assertEquals(before, repo.snapshot())
        } finally { db.close() }
    }
}
