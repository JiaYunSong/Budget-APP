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

/** Estimated allocation only; never posts daily interest into the cash ledger. */
fun Ledger.dailyInterest(date: LocalDate): List<Pair<Deposit, Long>> = deposits.filter {
    included(it) && date >= LocalDate.parse(it.startDate) && date < LocalDate.parse(it.settledAt ?: it.endDate)
}.map { d ->
    val value = if (d.interestMode == "DAY") java.math.BigDecimal(d.principal).multiply(java.math.BigDecimal(d.annualRateText)).movePointLeft(2)
        .divide(java.math.BigDecimal(365),0,java.math.RoundingMode.HALF_UP).longValueExact()
    else java.math.BigDecimal(d.interest()).divide(java.math.BigDecimal(java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(d.startDate),LocalDate.parse(d.endDate))),0,java.math.RoundingMode.HALF_UP).longValueExact()
    d to value
}
