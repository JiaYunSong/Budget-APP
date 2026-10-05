package com.local.deposittracker.core

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/** Pure, deterministic money operations. Persistence wraps every operation in a Room transaction. */
object Engine {
    private fun Ledger.balance(id: String, delta: Long, allowNegative: Boolean = true): Ledger {
        val a = accounts.find { it.id == id } ?: error("账户不存在")
        val newBalance = add(a.balance, delta)
        require(allowNegative || newBalance >= 0) { "当前账户余额不足。请确认后选择仍然创建。" }
        return copy(accounts = accounts.map { if (it.id == id) it.copy(balance = newBalance, updatedAt = now()) else it })
    }
    private fun Ledger.record(t: Transaction): Ledger {
        require(transactions.none { it.id == t.id }) { "事件已执行" }
        return copy(transactions = transactions + t)
    }
    fun createAccount(s: Ledger, name: String, opening: Long, include: Boolean, note: String): Ledger {
        require(name.isNotBlank()) { "请输入账户名称" }
        require(s.accounts.none { it.name == name.trim() }) { "账户名称已存在" }
        val a = Account(name = name.trim(), balance = opening, includeInTotal = include, note = note)
        return s.copy(accounts = s.accounts + a).record(Transaction(type = "ADJUSTMENT", amount = opening,
            date = today(), accountId = a.id, title = "${a.name} · 初始余额"))
    }
    fun change(s: Ledger, id: String, cents: Long, type: String, date: String, note: String): Ledger {
        require(cents != 0L) { "金额不能为零" }; require(LocalDate.parse(date) <= LocalDate.now()) { "实际流水不能使用未来日期" }
        require(type in setOf("MANUAL_INCOME", "MANUAL_EXPENSE", "ADJUSTMENT"))
        val a = s.accounts.first { it.id == id }
        return s.balance(id, cents, cents > 0).record(Transaction(type = type, amount = cents, date = date,
            accountId = id, title = "${a.name} · ${if (type == "ADJUSTMENT") "余额调整" else if (cents > 0) "外部存入" else "外部支出"}", note = note))
    }
    fun transfer(s: Ledger, from: String, to: String, cents: Long, date: String, note: String): Ledger {
        require(from != to && cents > 0) { "请选择不同账户并填写正数金额" }
        require(LocalDate.parse(date) <= LocalDate.now()) { "转账不能使用未来日期" }
        val names = s.accounts.associate { it.id to it.name }
        var result = s.balance(from, -cents, false).balance(to, cents)
        result = result.record(Transaction(type = "TRANSFER", amount = -cents, date = date, accountId = from, title = "转出至 ${names[to]}", note = note))
        return result.record(Transaction(type = "TRANSFER", amount = cents, date = date, accountId = to, title = "转入自 ${names[from]}", note = note))
    }
    fun validateDeposit(d: Deposit) {
        require(d.name.isNotBlank()) { "请输入产品名称" }
        require(d.principal > 0) { "本金必须大于零" }
        require(BigDecimal(d.annualRateText) >= BigDecimal.ZERO && BigDecimal(d.annualRateText) <= BigDecimal(100)) { "年利率需在 0–100% 之间" }
        require(d.annualRateText.matches(Regex("\\d{1,3}(\\.\\d{1,4})?"))) { "年利率最多四位小数" }
        require(LocalDate.parse(d.endDate) > LocalDate.parse(d.startDate)) { "到期日期必须晚于开始日期" }
        require(d.interestMode in setOf("DAY", "MONTH", "MANUAL")) { "计息方式不支持" }
        require(d.months in 1..1200) { "计息月数需在 1–1200 之间" }
        if (d.interestMode == "MANUAL") require(d.manualMaturityAmount != null && d.manualMaturityAmount >= 0) { "请填写非负到期金额" }
        d.maturity()
    }
    fun createDeposit(s: Ledger, d: Deposit, allowNegative: Boolean = false, currentDate: LocalDate = LocalDate.now()): Ledger {
        validateDeposit(d)
        require(LocalDate.parse(d.startDate) <= currentDate) { "不能提前扣除未来定期本金，请在开始日创建" }
        require(s.deposits.none { it.id == d.id }) { "产品已存在" }
        require(s.accounts.any { it.id == d.targetAccountId }) { "请选择到期账户" }
        val parent = d.parentDepositId?.let { id -> s.deposits.find { it.id == id } ?: error("转存来源不存在") }
        if (parent != null) require(parent.status == "MATURED") { "只有未转存的已到期产品可以转存" }
        val result = s.balance(d.sourceAccountId, -d.principal, allowNegative)
            .copy(deposits = s.deposits.map { if (it.id == parent?.id) it.copy(status = "ROLLED", updatedAt = now()) else it } + d)
            .record(Transaction(type = if (parent == null) "FIXED_DEPOSIT_CREATE" else "ROLLOVER", amount = -d.principal,
                date = d.startDate, accountId = d.sourceAccountId, relatedDepositId = d.id, title = if (parent == null) "存入 · ${d.name}" else "转存 · ${d.name}"))
        return settle(result, currentDate)
    }
    fun editDeposit(s: Ledger, id: String, name: String, note: String, target: String): Ledger {
        require(name.isNotBlank() && s.accounts.any { it.id == target }) { "请填写名称并选择到期账户" }
        val current = s.deposits.first { it.id == id }
        require(current.status == "ACTIVE" || target == current.targetAccountId) { "已结算产品不能修改回款账户，以保留实际资金历史" }
        return s.copy(deposits = s.deposits.map { if (it.id == id) it.copy(name = name, note = note, targetAccountId = target, updatedAt = now()) else it })
    }
    fun saveRule(s: Ledger, r: MonthlyRule): Ledger {
        require(r.name.isNotBlank() && r.amount > 0 && r.dayOfMonth in 1..31) { "请检查规则名称、金额和日期" }
        val start = LocalDate.parse(r.startDate); val end = r.endDate?.let(LocalDate::parse)
        require(end == null || end >= start) { "结束日期不能早于开始日期" }
        require(s.accounts.any { it.id == r.targetAccountId }) { "目标账户不存在" }
        val old = s.monthlyRules.find { it.id == r.id }
        require(old == null || old.lastProcessedDate == null || (old.amount == r.amount && old.dayOfMonth == r.dayOfMonth && old.startDate == r.startDate && old.targetAccountId == r.targetAccountId)) { "已执行规则只能修改名称、结束日期和启用状态；金额或日期变更请停用旧规则后新建" }
        return s.copy(monthlyRules = s.monthlyRules.filter { it.id != r.id } + r.copy(lastProcessedDate = old?.lastProcessedDate, updatedAt = now()))
    }
    fun deleteRule(s: Ledger, id: String): Ledger = s.copy(monthlyRules = s.monthlyRules.filter { it.id != id })
    private fun target(s: Ledger, d: Deposit): Pair<Ledger, String> {
        if (s.accounts.any { it.id == d.targetAccountId }) return s to d.targetAccountId
        if (s.accounts.any { it.id == d.sourceAccountId }) return s to d.sourceAccountId
        val a = s.accounts.find { it.name == "待处理余额" } ?: Account(name = "待处理余额", balance = 0)
        return (if (a in s.accounts) s else s.copy(accounts = s.accounts + a)) to a.id
    }
    private fun close(s: Ledger, d: Deposit, cents: Long, date: String, early: Boolean): Ledger {
        require(d.status == "ACTIVE" && d.settledAt == null) { "该产品已结算" }
        require(cents >= 0) { "到账金额不能为负数" }
        val (state, account) = target(s, d)
        val result = state.balance(account, cents).copy(deposits = state.deposits.map {
            if (it.id == d.id) it.copy(status = if (early) "EARLY" else "MATURED", settledAt = date,
                actualMaturityAmount = cents, updatedAt = now()) else it })
        return result.record(Transaction(id = "close:${d.id}", type = if (early) "FIXED_DEPOSIT_EARLY_WITHDRAW" else "FIXED_DEPOSIT_MATURE",
            amount = cents, date = date, accountId = account, relatedDepositId = d.id, title = "${if (early) "提前支取" else "到期回款"} · ${d.name}"))
    }
    fun earlyWithdraw(s: Ledger, id: String, cents: Long, date: String = today(), currentDate: LocalDate = LocalDate.now()): Ledger {
        val d = s.deposits.first { it.id == id }
        require(LocalDate.parse(date) <= currentDate) { "实际到账日期不能晚于今天" }
        require(LocalDate.parse(date) >= LocalDate.parse(d.startDate) && LocalDate.parse(date) < LocalDate.parse(d.endDate)) { "提前支取日期必须处于存期内" }
        return close(s, d, cents, date, true)
    }
    fun settle(input: Ledger, date: LocalDate): Ledger {
        var s = input
        // Monthly deposits always precede maturities; lastProcessedDate is a monotonic cursor.
        for (r in input.monthlyRules.filter { it.enabled }) {
            val start = LocalDate.parse(r.startDate)
            val through = r.endDate?.let(LocalDate::parse)?.coerceAtMost(date) ?: date
            var month = YearMonth.from(r.lastProcessedDate?.let(LocalDate::parse) ?: start)
            var cursor = r.lastProcessedDate
            while (month <= YearMonth.from(through)) {
                val due = month.atDay(r.dayOfMonth.coerceAtMost(month.lengthOfMonth()))
                if (due >= start && due <= through && (cursor == null || due > LocalDate.parse(cursor))) {
                    val id = "monthly:${r.id}:$due"
                    if (s.transactions.none { it.id == id }) s = s.balance(r.targetAccountId, r.amount).record(
                        Transaction(id = id, type = "MONTHLY_DEPOSIT", amount = r.amount, date = due.toString(),
                            accountId = r.targetAccountId, relatedRuleId = r.id, title = r.name))
                    cursor = due.toString()
                }
                month = month.plusMonths(1)
            }
            if (cursor != r.lastProcessedDate) s = s.copy(monthlyRules = s.monthlyRules.map { if (it.id == r.id) it.copy(lastProcessedDate = cursor, updatedAt = now()) else it })
        }
        for (d in s.deposits.filter { it.status == "ACTIVE" && it.settledAt == null && LocalDate.parse(it.endDate) <= date }.sortedBy { it.endDate })
            s = close(s, d, d.maturity(), d.endDate, false)
        return s
    }
}
