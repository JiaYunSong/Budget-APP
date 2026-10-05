package com.local.deposittracker.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.LocalDate

@Serializable
data class Backup(val version: Int = 1, val exportedAt: String = now(), val accounts: List<Account>,
    val deposits: List<Deposit>, val monthlyRules: List<MonthlyRule>, val transactions: List<Transaction>, val settings: Settings) {
    fun ledger(): Ledger = Ledger(accounts, deposits, monthlyRules, transactions, settings)
}
data class CsvPreview(val deposits: List<Deposit>, val errors: List<String>, val duplicates: Set<String>, val rowCount: Int)

object BackupCodec {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    fun export(s: Ledger): String = json.encodeToString(Backup(accounts = s.accounts, deposits = s.deposits,
        monthlyRules = s.monthlyRules, transactions = s.transactions, settings = s.settings))
    fun parse(text: String): Backup {
        require(text.length <= 10_000_000) { "备份文件超过 10MB" }
        val b = json.decodeFromString<Backup>(text.removePrefix("\uFEFF"))
        require(b.version == 1) { "不支持此备份版本" }
        validate(b.ledger()); java.time.LocalDateTime.parse(b.exportedAt)
        return b
    }
    fun validate(s: Ledger) {
        fun unique(ids: List<String>) { require(ids.size <= 100_000 && ids.all { it.isNotBlank() } && ids.distinct().size == ids.size) { "备份包含重复或无效 ID" } }
        unique(s.accounts.map { it.id }); unique(s.deposits.map { it.id }); unique(s.monthlyRules.map { it.id }); unique(s.transactions.map { it.id })
        require(s.accounts.map { it.name }.distinct().size == s.accounts.size) { "账户名称重复" }
        s.accounts.forEach { require(it.name.isNotBlank()); require(it.balance in -99_999_999_999_999..99_999_999_999_999) }
        val accountIds = s.accounts.map { it.id }.toSet(); val depositIds = s.deposits.map { it.id }.toSet()
        s.deposits.forEach { d ->
            Engine.validateDeposit(d)
            require(d.status in setOf("ACTIVE", "MATURED", "ROLLED", "EARLY")) { "产品状态无效" }
            require((d.status == "ACTIVE") == (d.settledAt == null)) { "结算状态与日期不一致" }
            require(d.status == "ACTIVE" || (d.actualMaturityAmount != null && d.actualMaturityAmount >= 0)) { "历史产品缺少实际到账金额" }
            require(d.sourceAccountId in accountIds && d.targetAccountId in accountIds) { "产品关联的账户不存在" }
            d.settledAt?.let { require(LocalDate.parse(it) >= LocalDate.parse(d.startDate)) }
            var parent = d.parentDepositId; val seen = mutableSetOf(d.id)
            while (parent != null) {
                require(parent in depositIds && seen.add(parent)) { "转存链缺失或循环" }
                parent = s.deposits.first { it.id == parent }.parentDepositId
            }
        }
        s.monthlyRules.forEach { r ->
            require(r.kind in setOf("MONTHLY_DEPOSIT", "FIXED_INCOME")) { "规则类型无效" }
            require(r.name.isNotBlank() && r.amount > 0 && r.dayOfMonth in 1..31 && r.targetAccountId in accountIds) { "自动存款规则无效" }
            val start = LocalDate.parse(r.startDate)
            r.endDate?.let { require(LocalDate.parse(it) >= start) }
            r.lastProcessedDate?.let { require(LocalDate.parse(it) >= start) }
        }
        s.transactions.forEach { t ->
            LocalDate.parse(t.date); require(t.accountId in accountIds) { "流水关联的账户不存在" }
            require(t.type in setOf("FIXED_INCOME", "MANUAL_INCOME", "MANUAL_EXPENSE", "MONTHLY_DEPOSIT", "FIXED_DEPOSIT_CREATE", "FIXED_DEPOSIT_MATURE", "FIXED_DEPOSIT_EARLY_WITHDRAW", "TRANSFER", "ROLLOVER", "ADJUSTMENT")) { "流水类型无效" }
            require(t.relatedDepositId == null || t.relatedDepositId in depositIds) { "流水关联的产品不存在" }
        }
        require(s.settings.theme in setOf("SYSTEM", "LIGHT", "DARK")) { "主题设置无效" }
        LocalDate.parse(s.settings.firstUsed); s.settings.lastBackup?.let(LocalDate::parse)
        s.total(); s.expectedInterest()
    }
}

object CsvCodec {
    private val header = listOf("名称", "机构", "类型", "本金", "年利率", "开始日期", "结束日期", "预计利息", "预计到期金额", "实际到期金额", "状态", "来源账户", "到期账户", "转存来源", "备注", "ID", "计息方式", "计息月数", "手动到期金额")
    private fun escape(s: String): String {
        // Prevent spreadsheet formulas when a bank name or note comes from untrusted input.
        val safe = if (s.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'" + s else s
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    private fun unescape(s: String): String = if (s.startsWith("'") && s.drop(1).trimStart().firstOrNull() in listOf('=', '+', '-', '@')) s.drop(1) else s
    fun fingerprint(d: Deposit): String = listOf(d.name, d.principal.toString(), d.startDate, d.endDate).joinToString("\u0000")
    fun export(s: Ledger): String {
        val accounts = s.accounts.associate { it.id to it.name }
        val lines = s.deposits.map { d -> listOf(d.name, d.institution, d.type, yuan(d.principal), d.annualRateText,
            d.startDate, d.endDate, yuan(d.interest()), yuan(d.maturity()), d.actualMaturityAmount?.let(::yuan) ?: "",
            d.statusLabel(), accounts[d.sourceAccountId] ?: "", accounts[d.targetAccountId] ?: "", d.parentDepositId ?: "", d.note,
            d.id, d.interestMode, d.months.toString(), d.manualMaturityAmount?.let(::yuan) ?: "") }
        return "\uFEFF" + (listOf(header) + lines).joinToString("\r\n") { it.joinToString(",", transform = ::escape) }
    }
    /** RFC 4180 parser: quoted commas, escaped quotes, CRLF and multiline notes. */
    fun rows(text: String): List<List<String>> {
        val input = text.removePrefix("\uFEFF"); val result = mutableListOf<List<String>>()
        val row = mutableListOf<String>(); val field = StringBuilder(); var quoted = false; var closed = false; var i = 0
        fun fieldDone() { row += unescape(field.toString()); field.setLength(0); closed = false }
        while (i < input.length) {
            val c = input[i]
            if (quoted) {
                if (c == '"') { if (i + 1 < input.length && input[i + 1] == '"') { field.append('"'); i++ } else { quoted = false; closed = true } }
                else field.append(c)
            } else when (c) {
                '"' -> { require(field.isEmpty() && !closed) { "CSV 引号位置错误" }; quoted = true }
                ',' -> fieldDone()
                '\n', '\r' -> { fieldDone(); if (row.any { it.isNotEmpty() }) result += row.toList(); row.clear(); if (c == '\r' && i + 1 < input.length && input[i + 1] == '\n') i++ }
                else -> { require(!closed) { "CSV 引号后含多余字符" }; field.append(c) }
            }
            i++
        }
        require(!quoted) { "CSV 引号未闭合" }
        if (field.isNotEmpty() || row.isNotEmpty() || closed) { fieldDone(); result += row.toList() }
        return result
    }
    fun preview(text: String, s: Ledger, currentDate: LocalDate = LocalDate.now()): CsvPreview {
        require(text.length <= 10_000_000) { "CSV 文件超过 10MB" }
        val rows = rows(text); require(rows.size in 2..10_001) { "CSV 需包含表头和 1–10000 条记录" }
        val h = rows.first(); require(h.distinct().size == h.size) { "CSV 表头不能重复" }
        require(listOf("名称", "本金", "年利率", "开始日期", "结束日期", "来源账户").all { it in h }) { "CSV 缺少必要表头" }
        val errors = mutableListOf<String>(); val result = mutableListOf<Deposit>(); val duplicates = mutableSetOf<String>()
        val fingerprints = s.deposits.map(::fingerprint).toMutableSet()
        rows.drop(1).forEachIndexed { index, values ->
            try {
                require(values.size == h.size) { "列数与表头不一致" }
                val m = h.zip(values).toMap(); fun get(key: String) = m[key].orEmpty().trim()
                // CSV is an asset onboarding format; settled history must use JSON to avoid double-credit.
                require(get("状态") in listOf("", "进行中", "即将到期")) { "历史已结算产品请使用 JSON 恢复，避免重复回款" }
                val source = s.accounts.find { it.name == get("来源账户") } ?: error("来源账户不存在，请先创建账户")
                val target = if (get("到期账户").isBlank()) source else s.accounts.find { it.name == get("到期账户") } ?: error("到期账户不存在")
                val start = try { LocalDate.parse(get("开始日期")) } catch (_: Exception) { error("开始日期格式错误（YYYY-MM-DD）") }
                val end = try { LocalDate.parse(get("结束日期")) } catch (_: Exception) { error("结束日期格式错误（YYYY-MM-DD）") }
                require(start <= currentDate) { "开始日期不能晚于今天" }
                val d = Deposit(name = get("名称"), institution = get("机构"), type = get("类型").ifBlank { "定期存款" }, principal = money(get("本金")),
                    annualRateText = get("年利率"), startDate = start.toString(), endDate = end.toString(), sourceAccountId = source.id, targetAccountId = target.id,
                    interestMode = get("计息方式").ifBlank { "DAY" }, months = get("计息月数").ifBlank { "3" }.toInt(),
                    manualMaturityAmount = get("手动到期金额").takeIf { it.isNotBlank() }?.let(::money), note = get("备注"))
                Engine.validateDeposit(d)
                if (!fingerprints.add(fingerprint(d))) duplicates += d.id
                result += d
            } catch (e: Exception) { errors += "第${index + 2}行：${userError(e)}" }
        }
        return CsvPreview(result, errors, duplicates, rows.size - 1)
    }
    fun import(s: Ledger, preview: CsvPreview, skipDuplicates: Boolean, allowNegative: Boolean, date: LocalDate = LocalDate.now()): Ledger {
        require(preview.errors.isEmpty()) { "请修正所有错误行后重新导入" }
        var result = s
        for (d in preview.deposits.filter { !skipDuplicates || it.id !in preview.duplicates }) result = Engine.createDeposit(result, d, allowNegative, date)
        return result
    }
}
