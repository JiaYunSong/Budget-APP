package com.local.deposittracker.core

import java.time.LocalDate
import java.time.YearMonth

data class AssetEvent(val date: String, val title: String, val cents: Long, val kind: String, val forecast: Boolean = false, val id: String = "")
fun Ledger.events(month: YearMonth, through: LocalDate = LocalDate.now()): List<AssetEvent> {
    val start = month.atDay(1).toString(); val end = month.atEndOfMonth().toString()
    return transactions.filter { it.date in start..end && it.date <= through.toString() }
        .map { AssetEvent(it.date, it.title, it.amount, it.type, id = it.id) }.sortedBy { it.date }
}
fun eventLabel(kind: String): String = when (kind) {
    "FIXED_INCOME" -> "固定收入"; "MONTHLY_DEPOSIT" -> "自动存款"; "FIXED_DEPOSIT_CREATE" -> "定期存入"; "FIXED_DEPOSIT_MATURE" -> "到期回款"
    "ROLLOVER" -> "转存"; "TRANSFER" -> "账户转账"; "FIXED_DEPOSIT_EARLY_WITHDRAW" -> "提前支取"
    "MANUAL_INCOME" -> "外部存入"; "MANUAL_EXPENSE" -> "外部支出"; else -> "手动调整"
}
