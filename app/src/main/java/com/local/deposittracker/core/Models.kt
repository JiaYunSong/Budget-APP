package com.local.deposittracker.core

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun today(): String = LocalDate.now().toString()
fun now(): String = java.time.LocalDateTime.now().toString()
fun money(text: String): Long {
    require(text.matches(Regex("-?\\d{1,12}(\\.\\d{1,2})?"))) { "金额最多两位小数，且不超过万亿元" }
    return BigDecimal(text).movePointRight(2).longValueExact()
}
fun yuan(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()
fun amount(cents: Long): String = "¥ " + java.text.DecimalFormat("#,##0.00").format(BigDecimal.valueOf(cents, 2))
fun add(a: Long, b: Long): Long = Math.addExact(a, b)
fun userError(e: Exception): String = when (e) {
    is java.time.format.DateTimeParseException -> "日期格式错误，请使用 YYYY-MM-DD"
    is NumberFormatException -> "数字格式错误，请使用小数点并检查输入"
    is ArithmeticException -> "金额超过支持范围，请检查本金、余额和收益"
    is kotlinx.serialization.SerializationException -> "备份格式错误或缺少必要字段，请选择完整 JSON 备份"
    is java.io.IOException -> "文件读写失败，请检查访问权限与存储空间"
    is SecurityException -> "无法访问所选文件，请通过系统文件选择器重新授权"
    else -> e.message?.takeIf { it.contains(Regex("[\\u4e00-\\u9fff]")) } ?: "数据校验失败，请检查输入字段与关联关系"
}

@Serializable @Entity(tableName = "accounts")
data class Account(@PrimaryKey val id: String = newId(), val name: String, val balance: Long,
    val includeInTotal: Boolean = true, val note: String = "", val createdAt: String = now(), val updatedAt: String = now())

@Serializable @Entity(tableName = "deposits")
data class Deposit(@PrimaryKey val id: String = newId(), val name: String, val institution: String = "",
    val type: String = "定期存款", val principal: Long, val annualRateText: String,
    val startDate: String, val endDate: String, val interestMode: String = "DAY", val months: Int = 3,
    val manualMaturityAmount: Long? = null, val sourceAccountId: String, val targetAccountId: String,
    val status: String = "ACTIVE", val parentDepositId: String? = null, val actualMaturityAmount: Long? = null,
    val settledAt: String? = null, val note: String = "", val archived: Boolean = false,
    val createdAt: String = now(), val updatedAt: String = now()) {
    fun interest(until: LocalDate = LocalDate.parse(endDate)): Long {
        if (interestMode == "MANUAL") return Math.subtractExact(requireNotNull(manualMaturityAmount), principal)
        val rate = BigDecimal(annualRateText).movePointLeft(2)
        val base = BigDecimal.valueOf(principal).multiply(rate)
        val interest = when (interestMode) {
            "MONTH" -> base.multiply(BigDecimal.valueOf(months.toLong())).divide(BigDecimal(12), 0, RoundingMode.HALF_UP)
            else -> {
                val days = ChronoUnit.DAYS.between(LocalDate.parse(startDate), until.coerceAtMost(LocalDate.parse(endDate))).coerceAtLeast(0)
                base.multiply(BigDecimal.valueOf(days)).divide(BigDecimal(365), 0, RoundingMode.HALF_UP)
            }
        }
        return interest.longValueExact()
    }
    fun maturity(): Long = add(principal, interest())
    fun days(date: LocalDate = LocalDate.now()): Long = ChronoUnit.DAYS.between(date, LocalDate.parse(endDate))
    fun statusLabel(date: LocalDate = LocalDate.now()): String = when (status) {
        "MATURED" -> "已到期"; "ROLLED" -> "已转存"; "EARLY" -> "已提前支取"
        else -> if (days(date) in 1..30) "即将到期" else "进行中"
    }
}

@Serializable @Entity(tableName = "monthly_rules")
data class MonthlyRule(@PrimaryKey val id: String = newId(), val name: String, val amount: Long,
    val dayOfMonth: Int, val startDate: String, val endDate: String? = null, val targetAccountId: String,
    val enabled: Boolean = true, val lastProcessedDate: String? = null,
    val createdAt: String = now(), val updatedAt: String = now())

@Serializable @Entity(tableName = "transactions")
data class Transaction(@PrimaryKey val id: String = newId(), val type: String, val amount: Long,
    val date: String, val accountId: String, val relatedDepositId: String? = null,
    val relatedRuleId: String? = null, val title: String, val note: String = "", val createdAt: String = now())

@Serializable
data class Settings(val theme: String = "SYSTEM", val hideMoney: Boolean = false,
    val includeAccruedInterest: Boolean = false, val reminders: Boolean = true,
    val welcomed: Boolean = false, val lastBackup: String? = null, val firstUsed: String = today())

@Serializable
data class Ledger(val accounts: List<Account> = emptyList(), val deposits: List<Deposit> = emptyList(),
    val monthlyRules: List<MonthlyRule> = emptyList(), val transactions: List<Transaction> = emptyList(),
    val settings: Settings = Settings()) {
    fun active(): List<Deposit> = deposits.filter { it.status == "ACTIVE" }
    fun cash(): Long = accounts.filter { it.includeInTotal }.fold(0L) { n, a -> add(n, a.balance) }
    fun principal(): Long = active().filter { included(it) }.fold(0L) { n, d -> add(n, d.principal) }
    fun included(d: Deposit): Boolean = accounts.find { it.id == d.sourceAccountId }?.includeInTotal ?: true
    fun expectedInterest(): Long = active().filter { included(it) }.fold(0L) { n, d -> add(n, d.interest()) }
    fun total(date: LocalDate = LocalDate.now()): Long = add(add(cash(), principal()),
        if (settings.includeAccruedInterest) active().filter { included(it) && it.interestMode == "DAY" }.fold(0L) { n, d -> add(n, d.interest(date)) } else 0)
}
