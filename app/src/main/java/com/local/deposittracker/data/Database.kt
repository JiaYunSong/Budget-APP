package com.local.deposittracker.data

import android.content.Context
import androidx.room.*
import com.local.deposittracker.core.*
import com.local.deposittracker.core.Transaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

@Entity(tableName = "settings")
data class SettingsRow(@PrimaryKey val id: Int = 1, val json: String)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM accounts ORDER BY createdAt") suspend fun accounts(): List<Account>
    @Query("SELECT * FROM deposits ORDER BY createdAt") suspend fun deposits(): List<Deposit>
    @Query("SELECT * FROM monthly_rules ORDER BY createdAt") suspend fun rules(): List<MonthlyRule>
    @Query("SELECT * FROM transactions ORDER BY date DESC, createdAt DESC") suspend fun transactions(): List<Transaction>
    @Query("SELECT * FROM settings WHERE id = 1") suspend fun settings(): SettingsRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAccounts(items: List<Account>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDeposits(items: List<Deposit>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRules(items: List<MonthlyRule>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTransactions(items: List<Transaction>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSettings(item: SettingsRow)
    @Query("DELETE FROM accounts") suspend fun clearAccounts()
    @Query("DELETE FROM deposits") suspend fun clearDeposits()
    @Query("DELETE FROM monthly_rules") suspend fun clearRules()
    @Query("DELETE FROM transactions") suspend fun clearTransactions()
}

@Database(entities = [Account::class, Deposit::class, MonthlyRule::class, Transaction::class, SettingsRow::class], version = 2, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun ledger(): LedgerDao
    companion object {
        fun open(context: Context): LedgerDatabase = Room.databaseBuilder(context, LedgerDatabase::class.java, "cunqi.db").addMigrations(MIGRATION_1_2).build()
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE monthly_rules ADD COLUMN kind TEXT NOT NULL DEFAULT 'MONTHLY_DEPOSIT'")
                db.execSQL("ALTER TABLE transactions ADD COLUMN transferGroupId TEXT")
            }
        }
    }
}

class LedgerRepository(private val db: LedgerDatabase) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(Ledger())
    val state = _state.asStateFlow()
    private suspend fun read(): Ledger {
        val d = db.ledger()
        return Ledger(d.accounts(), d.deposits(), d.rules(), d.transactions(),
            d.settings()?.let { Json.decodeFromString<Settings>(it.json) } ?: Settings())
    }
    private suspend fun write(s: Ledger) {
        val d = db.ledger()
        d.clearAccounts(); d.clearDeposits(); d.clearRules(); d.clearTransactions()
        d.putAccounts(s.accounts); d.putDeposits(s.deposits); d.putRules(s.monthlyRules); d.putTransactions(s.transactions)
        d.putSettings(SettingsRow(json = Json.encodeToString(s.settings)))
    }
    suspend fun update(operation: (Ledger) -> Ledger) = mutex.withLock {
        val result = db.withTransaction {
            val before = read()
            val after = operation(before)
            if (before != after) write(after)
            after
        }
        _state.value = result
    }
    suspend fun load() = update { it }
    suspend fun snapshot(): Ledger = mutex.withLock { db.withTransaction { read() } }
}
