package com.local.deposittracker.core

import java.time.LocalDate
import java.time.YearMonth

data class AssetEvent(val date: String, val title: String, val cents: Long, val kind: String, val forecast: Boolean = false)
fun Ledger.events(month: YearMonth): List<AssetEvent> {
    val start = month.atDay(1).toString(); val end = month.atEndOfMonth().toString()
    val result = transactions.filter { it.date in start..end }.map { AssetEvent(it.date, it.title, it.amount, it.type) }.toMutableList()
    monthlyRules.filter { it.enabled }.forEach { r ->
        val due = month.atDay(r.dayOfMonth.coerceAtMost(month.lengthOfMonth())).toString()
        if (due >= r.startDate && (r.endDate == null || due <= r.endDate) &&
            (r.lastProcessedDate == null || due > r.lastProcessedDate) && transactions.none { it.id == "monthly:${r.id}:$due" })
            result += AssetEvent(due, r.name, r.amount, "MONTHLY_DEPOSIT", true)
    }
    active().filter { it.endDate in start..end }.forEach { result += AssetEvent(it.endDate, "到期 · ${it.name}", it.maturity(), "FIXED_DEPOSIT_MATURE", true) }
    return result.sortedWith(compareBy({ it.date }, { it.forecast }))
}
fun eventLabel(kind: String): String = when (kind) {
    "MONTHLY_DEPOSIT" -> "自动存款"; "FIXED_DEPOSIT_CREATE" -> "定期存入"; "FIXED_DEPOSIT_MATURE" -> "到期回款"
    "ROLLOVER" -> "转存"; "TRANSFER" -> "账户转账"; "FIXED_DEPOSIT_EARLY_WITHDRAW" -> "提前支取"
    "MANUAL_INCOME" -> "外部存入"; "MANUAL_EXPENSE" -> "外部支出"; else -> "手动调整"
}
