@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.local.deposittracker.ui

import androidx.compose.foundation.layout.*
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.Instant
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
data class Editor(val title: String, val fields: List<Field>, val hint: String = "", val images: String = "[]", val save: (Map<String, String>, Ledger) -> Ledger)
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
    Field("amount", "金额（元）*", numeric = true), Field("date", "日期", date), Field("note", "备注")),
    "存款和回款是资产内部转移，请使用定期功能。更正余额会生成调整流水。") { v, state ->
    val cents = money(v.getValue("amount")); val id = v.getValue("account")
    val delta = when (v["kind"]) { "SET" -> Math.subtractExact(cents, state.accounts.first { it.id == id }.balance); "OUT" -> { require(cents > 0) { "请输入正数金额" }; -cents }; else -> { require(cents > 0) { "请输入正数金额" }; cents } }
    Engine.change(state, id, delta, if (v["kind"] == "SET") "ADJUSTMENT" else if (delta > 0) "MANUAL_INCOME" else "MANUAL_EXPENSE", v.getValue("date"), v["note"].orEmpty())
}
fun transferEditor(s: Ledger, date: String = today()): Editor = Editor("账户间转账", listOf(accountField("from", "转出账户", s),
    accountField("to", "转入账户", s, s.accounts.getOrNull(1)?.id.orEmpty()), Field("amount", "金额（元）*", numeric = true),
    Field("date", "日期", date), Field("note", "备注")), "转账只移动资产，不计入外部收入或支出。未来转账先预留转出账户资金，到日打开应用执行。") { v, state ->
    Engine.transfer(state, v.getValue("from"), v.getValue("to"), money(v.getValue("amount")), v.getValue("date"), v["note"].orEmpty())
}
fun depositEditor(s: Ledger, parent: Deposit? = null, date: String = today()): Editor {
    val source = if (parent == null) s.accounts.firstOrNull()?.id.orEmpty() else s.transactions.firstOrNull { it.id == "close:${parent.id}" }?.accountId ?: parent.targetAccountId
    return Editor(if (parent == null) "新增定期 / 固定收益" else "转存 · ${parent.name}", listOf(
        Field("name", "产品名称 *", parent?.name.orEmpty()), Field("bank", "银行 / 机构", parent?.institution.orEmpty()),
        Field("type", "产品类型", parent?.type ?: "定期存款", listOf("定期存款", "大额存单", "固定收益理财", "国债", "其他固定收益").map { it to it }),
        Field("principal", "本金（元）*", parent?.actualMaturityAmount?.let(::yuan).orEmpty(), numeric = true),
        Field("rate", "年利率（%，最多四位小数）*", if (parent == null) "1.3500" else "", numeric = true),
        Field("start", "开始日期", parent?.endDate ?: date), Field("end", "到期日期", LocalDate.parse(parent?.endDate ?: date).plusMonths(3).toString()),
        Field("mode", "计息方式", "DAY", listOf("DAY" to "按实际天数 / 365", "MONTH" to "按月 / 12", "MANUAL" to "手动填写到期金额")),
        Field("months", "计息月数（按月时使用）", "3", numeric = true), Field("manual", "手动到期金额（元）", numeric = true),
        accountField("source", "购买投资的银行账户 *", s, source), accountField("target", "到期账户（默认来源账户）", s, source),
        Field("pretransfer", "购买前从其他账户转账", "false", boolean),
        accountField("transferFrom", "转账来源银行账户", s, source), Field("note", "备注")),
        "允许负余额；未来开始的产品先标为“未来定期”，预留活期并在开始日打开应用时扣款。购买及账户间转账不会改变合计总资产。勾选转账时，先转入购买银行账户再扣除本金。到期后回款至指定账户。已开始产品的本金、日期和利率不可直接修改。历史已到期产品会立即结算。") { v, state ->
        Engine.invest(state, Deposit(name = v.getValue("name"), institution = v["bank"].orEmpty().ifBlank { state.accounts.first { it.id == v["source"] }.name }, type = v.getValue("type"),
            principal = money(v.getValue("principal")), annualRateText = v.getValue("rate"), startDate = v.getValue("start"), endDate = v.getValue("end"),
            interestMode = v.getValue("mode"), months = if (v["mode"] == "MONTH") v.getValue("months").toInt() else 3, manualMaturityAmount = if (v["mode"] == "MANUAL") money(v.getValue("manual")) else null,
            sourceAccountId = v.getValue("source"), targetAccountId = v["target"].orEmpty().ifBlank { v.getValue("source") }, parentDepositId = parent?.id, note = v["note"].orEmpty()), if (v["pretransfer"] == "true") v.getValue("transferFrom") else null)
    }
}
fun editDepositEditor(s: Ledger, d: Deposit): Editor = Editor("编辑产品信息", listOf(Field("name", "产品名称", d.name)) +
    (if (d.status in setOf("ACTIVE", "PLANNED")) listOf(accountField("target", "到期账户", s, d.targetAccountId)) else emptyList()) + Field("note", "备注", d.note),
    "本金、开始日期及计息条件已锁定。已结算产品仅允许修改名称和备注，以保护实际回款历史。", images = d.imagesJson) { v, state ->
    Engine.editDeposit(state, d.id, v.getValue("name"), v["note"].orEmpty(), v["target"] ?: d.targetAccountId).let { edited -> edited.copy(deposits = edited.deposits.map { if (it.id == d.id) it.copy(imagesJson = v["images"] ?: d.imagesJson) else it }) }
}
fun earlyEditor(d: Deposit): Editor = Editor("提前支取 · ${d.name}", listOf(Field("amount", "实际到账金额（元）*", numeric = true),
    Field("date", "实际到账日期", today())), "本金 ${amount(d.principal)}。请按银行实际回款填写，不按原定年利率自动计算。", images = d.imagesJson) { v, state ->
    Engine.earlyWithdraw(state.copy(deposits = state.deposits.map { if (it.id == d.id) it.copy(imagesJson = v["images"] ?: d.imagesJson) else it }), d.id, money(v.getValue("amount")), v.getValue("date"))
}
fun ruleEditor(s: Ledger, rule: MonthlyRule? = null, kind: String = rule?.kind ?: "MONTHLY_DEPOSIT", date: String = today()): Editor = Editor(if (kind == "FIXED_INCOME") "固定收入规则" else if (rule == null) "新增自动月存" else "编辑自动月存", listOf(
    Field("name", "规则名称 *", rule?.name ?: if (kind == "FIXED_INCOME") "每月固定收入" else "每月工资结余"), Field("amount", "每月金额（元）*", rule?.amount?.let(::yuan).orEmpty(), numeric = true),
    Field("day", "每月几日（1–31）*", (rule?.dayOfMonth ?: 15).toString(), options = (1..31).map { it.toString() to "${it}日" }),
    Field("start", "开始日期", rule?.startDate ?: date), Field("end", "结束日期（留空为长期）", rule?.endDate.orEmpty()),
    accountField("target", "存入账户 *", s, rule?.targetAccountId ?: s.accounts.firstOrNull()?.id.orEmpty()),
    Field("enabled", "启用规则", (rule?.enabled ?: true).toString(), boolean)),
    "这是一条本地记账规则，不会操作银行资金。打开应用时补记过去应存入的月份；31 日在短月按月底执行。已执行规则的金额和日期不可改动。", images = rule?.imagesJson ?: "[]") { v, state ->
    Engine.settle(Engine.saveRule(state, MonthlyRule(id = rule?.id ?: newId(), name = v.getValue("name"), amount = money(v.getValue("amount")),
        dayOfMonth = v.getValue("day").toInt(), startDate = v.getValue("start"), endDate = v["end"]?.takeIf { it.isNotBlank() }, targetAccountId = v.getValue("target"),
        imagesJson = v["images"] ?: rule?.imagesJson ?: "[]", kind = kind, enabled = v["enabled"] == "true", lastProcessedDate = rule?.lastProcessedDate, createdAt = rule?.createdAt ?: now())), java.time.LocalDate.now())
}

@Composable
fun EditorDialog(editor: Editor, busy: Boolean, close: () -> Unit, save: (Map<String, String>) -> Unit) {
    var imageBusy by remember(editor) { mutableStateOf(false) }
    var images by remember(editor) { mutableStateOf(editor.images) }
    var pickingDate by remember(editor) { mutableStateOf<Field?>(null) }
    val values = remember(editor) { mutableStateMapOf<String, String>().apply { editor.fields.forEach { put(it.key, it.default) } } }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(editor.title) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (editor.hint.isNotEmpty()) Text(editor.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                editor.fields.filter { f -> (f.key != "months" || values["mode"] == "MONTH") && (f.key != "manual" || values["mode"] == "MANUAL") && (f.key != "transferFrom" || values["pretransfer"] == "true") }.forEach { f ->
                    if (f.label.contains("日期")) {
                        Text(f.label, style = MaterialTheme.typography.labelMedium)
                        OutlinedButton(onClick = { pickingDate = f }, modifier = Modifier.fillMaxWidth()) { Text(values[f.key].orEmpty().ifBlank { "选择日期" }) }
                        if (f.label.contains("留空")) TextButton(onClick = { values[f.key] = "" }) { Text("长期 · 清除结束日期") }
                    } else if (f.options.isNotEmpty()) ChoiceField(f.label, values[f.key].orEmpty(), f.options) { selected -> if (f.key == "source" && values["target"] == values["source"]) values["target"] = selected; values[f.key] = selected }
                    else OutlinedTextField(value = values[f.key].orEmpty(), onValueChange = { values[f.key] = it }, label = { Text(f.label) },
                        modifier = Modifier.fillMaxWidth(), singleLine = f.key != "note", readOnly = editor.title == "编辑活期账户" && f.key == "balance",
                        keyboardOptions = KeyboardOptions(keyboardType = if (f.numeric) KeyboardType.Decimal else KeyboardType.Text))
                }
                if (!editor.title.contains("账户" ) || editor.title == "账户间转账") AttachmentEditor(images, loadingChanged = { imageBusy = it }) { images = it }
            }
        }, confirmButton = { Button(enabled = !busy && !imageBusy, onClick = { save(values.toMap() + ("images" to images)) }) { Text(if (busy) "正在保存…" else "保存") } },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Text("取消") } })
    pickingDate?.let { field ->
        val initial = values[field.key]?.takeIf { it.isNotBlank() }?.let(LocalDate::parse) ?: LocalDate.now()
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = field.key == "end" || field.key == "start" || editor.title == "账户间转账" || (editor.title in listOf("一次性取款", "一次性存款") && values["kind"] == "OUT") || editor.title.contains("月存") || editor.title.contains("固定收入") || Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate() <= LocalDate.now()
            })
        DatePickerDialog(onDismissRequest = { pickingDate = null }, confirmButton = { TextButton(enabled = picker.selectedDateMillis != null, onClick = {
            values[field.key] = Instant.ofEpochMilli(requireNotNull(picker.selectedDateMillis)).atZone(ZoneOffset.UTC).toLocalDate().toString(); pickingDate = null
        }) { Text("确定日期") } }, dismissButton = { TextButton(onClick = { pickingDate = null }) { Text("取消") } }) {
            DatePicker(state = picker, showModeToggle = false)
        }
    }

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

fun transactionEditor(t: Transaction): Editor = Editor("记账明细", listOf(Field("note", "备注", t.note)), hint = "可修改备注和单笔截图；自动记录会沿用关联规则或投资的截图。", images = t.imagesJson) { values, state ->
    state.copy(transactions = state.transactions.map { if (it.id == t.id) it.copy(note = values["note"].orEmpty(), imagesJson = values["images"] ?: "[]") else it })
}
