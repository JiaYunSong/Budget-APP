package com.local.deposittracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.local.deposittracker.core.*

data class Field(val key: String, val label: String, val default: String = "", val options: List<Pair<String, String>> = emptyList(), val numeric: Boolean = false)
data class Editor(val title: String, val fields: List<Field>, val hint: String = "", val save: (Map<String, String>, Ledger) -> Ledger)
private fun choices(s: Ledger) = s.accounts.map { it.id to it.name }
private fun accountField(key: String, label: String, s: Ledger, selected: String = s.accounts.firstOrNull()?.id.orEmpty()) = Field(key, label, selected, choices(s))
private val boolean = listOf("true" to "是", "false" to "否")

fun accountEditor(s: Ledger, account: Account? = null): Editor = Editor(if (account == null) "创建活期账户" else "编辑活期账户", listOf(
    Field("name", "账户名称 *", account?.name.orEmpty()),
    Field("balance", if (account == null) "初始余额（元）*" else "当前余额（余额调整请使用活期变动）", yuan(account?.balance ?: 0), numeric = true),
    Field("included", "计入总资产", (account?.includeInTotal ?: true).toString(), boolean),
    Field("note", "备注", account?.note.orEmpty())),
    hint = if (account == null) "初始余额会生成调整流水。金额以人民币元输入。" else "编辑不会修改余额，保留所有历史流水。") { v, state ->
    if (account == null) Engine.createAccount(state, v.getValue("name"), money(v.getValue("balance")), v["included"] == "true", v["note"].orEmpty())
    else {
        require(v["name"].orEmpty().isNotBlank()) { "请输入账户名称" }
        require(state.accounts.none { it.id != account.id && it.name == v["name"] }) { "账户名称已存在" }
        state.copy(accounts = state.accounts.map { if (it.id == account.id) it.copy(name = v.getValue("name"), includeInTotal = v["included"] == "true", note = v["note"].orEmpty(), updatedAt = now()) else it })
    }
}
fun cashEditor(s: Ledger, direction: String = "IN", date: String = today()): Editor = Editor(if (direction == "OUT") "一次性取款" else "一次性存款", listOf(
    accountField("account", "账户 *", s),
    Field("kind", "变动类型", direction, listOf("IN" to "外部存入", "OUT" to "外部支出", "SET" to "更正为指定余额")),
    Field("amount", "金额（元）*", numeric = true), Field("date", "日期 YYYY-MM-DD", date), Field("note", "备注")),
    "存款和回款是资产内部转移，请使用定期功能。更正余额会生成调整流水。") { v, state ->
    val cents = money(v.getValue("amount")); val id = v.getValue("account")
    val delta = when (v["kind"]) { "SET" -> Math.subtractExact(cents, state.accounts.first { it.id == id }.balance); "OUT" -> { require(cents > 0) { "请输入正数金额" }; -cents }; else -> { require(cents > 0) { "请输入正数金额" }; cents } }
    Engine.change(state, id, delta, if (v["kind"] == "SET") "ADJUSTMENT" else if (delta > 0) "MANUAL_INCOME" else "MANUAL_EXPENSE", v.getValue("date"), v["note"].orEmpty())
}
fun transferEditor(s: Ledger, date: String = today()): Editor = Editor("账户间转账", listOf(accountField("from", "转出账户", s),
    accountField("to", "转入账户", s, s.accounts.getOrNull(1)?.id.orEmpty()), Field("amount", "金额（元）*", numeric = true),
    Field("date", "日期 YYYY-MM-DD", date), Field("note", "备注")), "转账只移动资产，不计入外部收入或支出。") { v, state ->
    Engine.transfer(state, v.getValue("from"), v.getValue("to"), money(v.getValue("amount")), v.getValue("date"), v["note"].orEmpty())
}
fun depositEditor(s: Ledger, parent: Deposit? = null, date: String = today()): Editor {
    val source = if (parent == null) s.accounts.firstOrNull()?.id.orEmpty() else s.transactions.firstOrNull { it.id == "close:${parent.id}" }?.accountId ?: parent.targetAccountId
    return Editor(if (parent == null) "新增定期 / 固定收益" else "转存 · ${parent.name}", listOf(
        Field("name", "产品名称 *", parent?.name.orEmpty()), Field("bank", "银行 / 机构", parent?.institution.orEmpty()),
        Field("type", "产品类型", parent?.type ?: "定期存款", listOf("定期存款", "大额存单", "固定收益理财", "国债", "其他固定收益").map { it to it }),
        Field("principal", "本金（元）*", parent?.actualMaturityAmount?.let(::yuan).orEmpty(), numeric = true),
        Field("rate", "年利率（%，最多四位小数）*", if (parent == null) "1.3500" else "", numeric = true),
        Field("start", "开始日期 YYYY-MM-DD", parent?.endDate ?: date), Field("end", "到期日期 YYYY-MM-DD", ""),
        Field("mode", "计息方式", "DAY", listOf("DAY" to "按实际天数 / 365", "MONTH" to "按月 / 12", "MANUAL" to "手动填写到期金额")),
        Field("months", "计息月数（按月时使用）", "3", numeric = true), Field("manual", "手动到期金额（元）", numeric = true),
        accountField("source", "来源账户 *", s, source), accountField("target", "到期账户（默认来源账户）", s, source),
        Field("negative", "余额不足时仍然创建", "false", boolean), Field("note", "备注")),
        "保存时从来源账户扣除本金。到期后回款至指定账户。已开始产品的本金、日期和利率不可直接修改。历史已到期产品会立即结算。") { v, state ->
        Engine.createDeposit(state, Deposit(name = v.getValue("name"), institution = v["bank"].orEmpty(), type = v.getValue("type"),
            principal = money(v.getValue("principal")), annualRateText = v.getValue("rate"), startDate = v.getValue("start"), endDate = v.getValue("end"),
            interestMode = v.getValue("mode"), months = v.getValue("months").toInt(), manualMaturityAmount = v["manual"]?.takeIf { it.isNotBlank() }?.let(::money),
            sourceAccountId = v.getValue("source"), targetAccountId = v["target"].orEmpty().ifBlank { v.getValue("source") }, parentDepositId = parent?.id, note = v["note"].orEmpty()), v["negative"] == "true")
    }
}
fun editDepositEditor(s: Ledger, d: Deposit): Editor = Editor("编辑产品信息", listOf(Field("name", "产品名称", d.name)) +
    (if (d.status == "ACTIVE") listOf(accountField("target", "到期账户", s, d.targetAccountId)) else emptyList()) + Field("note", "备注", d.note),
    "本金、开始日期及计息条件已锁定。已结算产品仅允许修改名称和备注，以保护实际回款历史。") { v, state ->
    Engine.editDeposit(state, d.id, v.getValue("name"), v["note"].orEmpty(), v["target"] ?: d.targetAccountId)
}
fun earlyEditor(d: Deposit): Editor = Editor("提前支取 · ${d.name}", listOf(Field("amount", "实际到账金额（元）*", numeric = true),
    Field("date", "实际到账日期 YYYY-MM-DD", today())), "本金 ${amount(d.principal)}。请按银行实际回款填写，不按原定年利率自动计算。") { v, state ->
    Engine.earlyWithdraw(state, d.id, money(v.getValue("amount")), v.getValue("date"))
}
fun ruleEditor(s: Ledger, rule: MonthlyRule? = null, kind: String = rule?.kind ?: "MONTHLY_DEPOSIT", date: String = today()): Editor = Editor(if (kind == "FIXED_INCOME") "固定收入规则" else if (rule == null) "新增自动月存" else "编辑自动月存", listOf(
    Field("name", "规则名称 *", rule?.name ?: if (kind == "FIXED_INCOME") "每月固定收入" else "每月工资结余"), Field("amount", "每月金额（元）*", rule?.amount?.let(::yuan).orEmpty(), numeric = true),
    Field("day", "每月几日（1–31）*", (rule?.dayOfMonth ?: 15).toString(), numeric = true),
    Field("start", "开始日期 YYYY-MM-DD", rule?.startDate ?: date), Field("end", "结束日期（留空为长期）", rule?.endDate.orEmpty()),
    accountField("target", "存入账户 *", s, rule?.targetAccountId ?: s.accounts.firstOrNull()?.id.orEmpty()),
    Field("enabled", "启用规则", (rule?.enabled ?: true).toString(), boolean)),
    "这是一条本地记账规则，不会操作银行资金。打开应用时补记过去应存入的月份；31 日在短月按月底执行。已执行规则的金额和日期不可改动。") { v, state ->
    Engine.settle(Engine.saveRule(state, MonthlyRule(id = rule?.id ?: newId(), name = v.getValue("name"), amount = money(v.getValue("amount")),
        dayOfMonth = v.getValue("day").toInt(), startDate = v.getValue("start"), endDate = v["end"]?.takeIf { it.isNotBlank() }, targetAccountId = v.getValue("target"),
        kind = kind, enabled = v["enabled"] == "true", lastProcessedDate = rule?.lastProcessedDate, createdAt = rule?.createdAt ?: now())), java.time.LocalDate.now())
}

@Composable
fun EditorDialog(editor: Editor, busy: Boolean, close: () -> Unit, save: (Map<String, String>) -> Unit) {
    val values = remember(editor) { mutableStateMapOf<String, String>().apply { editor.fields.forEach { put(it.key, it.default) } } }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(editor.title) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (editor.hint.isNotEmpty()) Text(editor.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                editor.fields.forEach { f ->
                    if (f.options.isNotEmpty()) ChoiceField(f.label, values[f.key].orEmpty(), f.options) { values[f.key] = it }
                    else OutlinedTextField(value = values[f.key].orEmpty(), onValueChange = { values[f.key] = it }, label = { Text(f.label) },
                        modifier = Modifier.fillMaxWidth(), singleLine = f.key != "note", readOnly = editor.title == "编辑活期账户" && f.key == "balance",
                        keyboardOptions = KeyboardOptions(keyboardType = if (f.numeric) KeyboardType.Decimal else KeyboardType.Text))
                }
            }
        }, confirmButton = { Button(enabled = !busy, onClick = { save(values.toMap()) }) { Text(if (busy) "正在保存…" else "保存") } },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Text("取消") } })
}

@Composable
fun ChoiceField(label: String, value: String, choices: List<Pair<String, String>>, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(choices.find { it.first == value }?.second ?: "请选择", modifier = Modifier.weight(1f)); Text("⌄") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { choices.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { select(id); expanded = false }) } }
        }
    }
}
