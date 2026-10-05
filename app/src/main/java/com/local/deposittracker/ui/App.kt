@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.local.deposittracker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import com.local.deposittracker.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import com.local.deposittracker.AppViewModel
import com.local.deposittracker.core.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

private val LocalHidden = compositionLocalOf { false }
@Composable private fun display(cents: Long): String = if (LocalHidden.current) "¥ ••••••" else amount(cents)
private val Light = lightColorScheme(primary = Color(0xFF875C73), onPrimary = Color.White,
    primaryContainer = Color(0xFFF2DEE8), secondary = Color(0xFF92784D), background = Color(0xFFFFF8F3),
    surface = Color(0xFFFFFBF8), surfaceVariant = Color(0xFFF0E7ED), onSurface = Color(0xFF382F36))
private val Dark = darkColorScheme(primary = Color(0xFFE5BDD2), onPrimary = Color(0xFF402837),
    primaryContainer = Color(0xFF54394A), secondary = Color(0xFFD9C09F), background = Color(0xFF201A20),
    surface = Color(0xFF2B222B), surfaceVariant = Color(0xFF40323E))

@Composable
fun CunqiApp(vm: AppViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val dark = when (s.settings.theme) { "LIGHT" -> false; "DARK" -> true; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light, shapes = Shapes(small = RoundedCornerShape(16.dp), medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(32.dp))) {
        CompositionLocalProvider(LocalHidden provides s.settings.hideMoney) {
            val snack = remember { SnackbarHostState() }
            LaunchedEffect(vm.message) { vm.message?.takeIf { it.isNotEmpty() }?.let { snack.showSnackbar(it) }; vm.message = null }
            val lifecycle = LocalLifecycleOwner.current
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.settle() }
                lifecycle.lifecycle.addObserver(observer)
                onDispose { lifecycle.lifecycle.removeObserver(observer) }
            }
            val nav = rememberNavController()
            val entry by nav.currentBackStackEntryAsState()
            val route = entry?.destination?.route ?: "home"
            var editor by remember { mutableStateOf<Editor?>(null) }
            var selected by remember { mutableStateOf<String?>(null) }
            var addMenu by remember { mutableStateOf(false) }
            var recordDate by remember { mutableStateOf(today()) }
            var scope by remember { mutableStateOf(setOf<String>()) }
            var accountsMenu by remember { mutableStateOf(false) }
            var manageAccounts by remember { mutableStateOf(false) }
            var pendingDelete by remember { mutableStateOf<String?>(null) }
            val view = s.scoped(scope)
            val formState = s.copy(accounts = s.accounts.sortedBy { if (it.id in scope) 0 else 1 })
            val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let { uri -> vm.export(uri, false) } }
            val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let { uri -> vm.export(uri, true) } }
            val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> vm.preview(uri, false) } }
            val importCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> vm.preview(uri, true) } }
            Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snack) },
                topBar = { TopAppBar(title = { Column { Text("存期", fontWeight = FontWeight.Bold); Text(if (scope.isEmpty()) "全部账户 · 让积蓄慢慢生长" else s.accounts.filter { it.id in scope }.joinToString(" · ") { it.name }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                    actions = { IconButton(onClick = { accountsMenu = true }) { Icon(Icons.Outlined.AccountBalanceWallet, "选择统计账户") }; IconButton(onClick = { vm.act(success = "") { it.copy(settings = it.settings.copy(hideMoney = !it.settings.hideMoney)) } }) {
                        Icon(if (s.settings.hideMoney) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (s.settings.hideMoney) "显示金额" else "隐藏金额") } }) },
                bottomBar = { NavigationBar { listOf("home" to "首页", "calendar" to "日历", "deposits" to "存款", "settings" to "我的").forEachIndexed { i, (r, label) ->
                    NavigationBarItem(selected = route == r, onClick = { nav.navigate(r) { popUpTo(nav.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true }; if (r == "home") vm.settle() },
                        icon = { Icon(listOf(Icons.Outlined.Home, Icons.Outlined.CalendarMonth, Icons.Outlined.Savings, Icons.Outlined.PersonOutline)[i], label) }, label = { Text(label) })
                } } },
                floatingActionButton = { if (vm.ready && route != "settings") FloatingActionButton(onClick = { recordDate = today(); addMenu = true }) { Icon(Icons.Outlined.Add, "新增资产或规则") } }
            ) { padding ->
                if (!vm.ready) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text("正在读取本地资产…") } }
                else NavHost(navController = nav, startDestination = "home", modifier = Modifier.padding(padding)) {
                    composable("home") { Home(view, { selected = it }, { editor = accountEditor(s) }, { editor = cashEditor(s) }, { pendingDelete = it }) }
                    composable("calendar") { CalendarScreen(view, { date -> recordDate = date; addMenu = true }, { pendingDelete = it }, { id -> editor = transactionEditor(s.transactions.first { it.id == id }) }) }
                    composable("deposits") { DepositsScreen(view, { selected = it }, { editor = accountEditor(s, it) }, { editor = ruleEditor(s, it) },
                        { r -> vm.act("状态已更新") { state -> Engine.settle(state.copy(monthlyRules = state.monthlyRules.map { if (it.id == r.id) it.copy(enabled = !it.enabled, updatedAt = now()) else it }), LocalDate.now()) } },
                        { r -> vm.act("规则及对应存款已清除") { Engine.deleteRule(it, r.id) } }, { editor = transferEditor(s) }) }
                    composable("settings") { SettingsScreen(s, vm,
                        { exportJson.launch("存期_backup_${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))}.json") },
                        { exportCsv.launch("deposits_${today()}.csv") }, { importJson.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        { importCsv.launch(arrayOf("text/*", "application/csv", "application/octet-stream")) }) }
                }
                if (vm.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(padding))
            }
            if (vm.ready && !s.settings.welcomed) AlertDialog(onDismissRequest = {}, title = { Text("欢迎使用「存期」") }, text = { Text("管理你的活期、定期和固定收益资产。\n\n数据只保存在你的手机，不需要账号或网络。卸载应用会删除本地数据，建议定期导出完整备份。\n\n自动月存仅记录截至今天的实际存款，不会替你操作银行账户。") }, confirmButton = { Button(onClick = {
                vm.act(success = "", done = { if (s.accounts.isEmpty()) editor = accountEditor(s) }) { it.copy(settings = it.settings.copy(welcomed = true)) }
            }) { Text("开始使用") } })
            if (addMenu) AlertDialog(onDismissRequest = { addMenu = false }, title = { Text("新增") }, text = {
                Column { listOf("一次性存款", "一次性取款", "固定收入", "投资", "自动月存", "账户间转账").forEachIndexed { i, title ->
                    TextButton(onClick = {
                        if (s.accounts.isEmpty()) { vm.message = "请先创建一个活期账户"; editor = accountEditor(s) }
                        else editor = when (i) { 0 -> cashEditor(formState, date = recordDate); 1 -> cashEditor(formState, "OUT", recordDate); 2 -> ruleEditor(formState, kind = "FIXED_INCOME", date = recordDate); 3 -> depositEditor(formState, date = recordDate); 4 -> ruleEditor(formState, date = recordDate); else -> transferEditor(formState, recordDate) }
                        addMenu = false
                    }, modifier = Modifier.fillMaxWidth()) { Text(title) }
                } }
            }, confirmButton = { TextButton(onClick = { addMenu = false }) { Text("取消") } })
            if (manageAccounts) AccountManager(s, vm.busy, { manageAccounts = false }) { ids, target -> vm.act("账户已删除，余额和关联记录已处理", done = { manageAccounts = false; scope = scope - ids }) { Engine.deleteAccounts(it, ids, target) } }
            if (accountsMenu) AlertDialog(onDismissRequest = { accountsMenu = false }, title = { Text("统计账户") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("选择多个账户合并统计；不选择则显示全部账户。")
                    TextButton(onClick = { scope = emptySet() }) { Text("全部账户") }
                    s.accounts.forEach { a -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(a.id in scope, { checked -> scope = if (checked) scope + a.id else scope - a.id }); Text(a.name)
                    } }
                    OutlinedButton(onClick = { accountsMenu = false; manageAccounts = true }) { Text("管理 / 勾选删除账户") }
                    OutlinedButton(onClick = { accountsMenu = false; editor = accountEditor(s) }) { Text("新建账户") }
                }
            }, confirmButton = { TextButton(onClick = { accountsMenu = false }) { Text("完成") } })
            pendingDelete?.let { id -> AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("删除这笔流水？") }, text = {
                Text("未来计划尚未扣款，取消后会释放预留。已发生流水将撤销对应账户金额。转账会同时撤销两端；投资会删除该产品及后续转存和回款。已使用的金额被撤销后，账户可能出现负余额。月存规则仍会继续执行，已删除月份不会补回。")
            }, confirmButton = { TextButton(onClick = { vm.act("流水已删除", done = { pendingDelete = null }) { Engine.deleteTransaction(it, id) } }) { Text("确认删除") } }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }) }
            editor?.let { form -> EditorDialog(form, vm.busy, { editor = null }) { values -> vm.act(done = { editor = null }) { Pictures.annotate(it, form.save(values, it), values["images"] ?: "[]") } } }
            selected?.let { id -> s.deposits.find { it.id == id }?.let { d -> DepositDetail(d, s, { selected = null },
                { selected = null; editor = editDepositEditor(s, d) }, { selected = null; editor = earlyEditor(d) },
                { selected = null; editor = depositEditor(s, d) }, { vm.act("产品已归档") { state -> state.copy(deposits = state.deposits.map { if (it.id == id) it.copy(archived = true) else it }) }; selected = null }) } }
            vm.backupPreview?.let { backup -> AlertDialog(onDismissRequest = { vm.backupPreview = null }, title = { Text("恢复完整备份？") }, text = {
                Text("备份时间：${backup.exportedAt}\n${backup.accounts.size} 个账户\n${backup.deposits.size} 笔定期\n${backup.monthlyRules.size} 条规则\n${backup.transactions.size} 条流水\n\n将完整覆盖本机数据和设置，无法撤销。建议先导出当前备份。恢复完成后可刷新首页补记到今天。") },
                confirmButton = { Button(enabled = !vm.busy, onClick = { vm.act("完整备份已恢复", { vm.backupPreview = null }) { BackupCodec.validate(backup.ledger()); backup.ledger() } }) { Text("覆盖当前数据") } },
                dismissButton = { TextButton(onClick = { vm.backupPreview = null }) { Text("取消") } }) }
            vm.csvPreview?.let { preview -> CsvImportDialog(preview, vm.busy, { vm.csvPreview = null }) { skip, negative ->
                vm.act("CSV 已导入，资金流水已生成", { vm.csvPreview = null }) { CsvCodec.import(it, preview, skip, negative) }
            } }
        }
    }
}

@Composable private fun Section(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}
@Composable private fun Empty(title: String, description: String) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}
@Composable private fun ValueRow(label: String, cents: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(display(cents), fontWeight = FontWeight.Medium) }
}
@Composable private fun StatCard(title: String, value: Long, modifier: Modifier = Modifier) {
    Card(modifier) { Column(Modifier.padding(16.dp)) { Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(display(value), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) } }
}
@Composable
private fun Home(s: Ledger, detail: (String) -> Unit, create: () -> Unit, cash: () -> Unit, cancelPlan: (String) -> Unit) {
    val month = YearMonth.now(); val events = s.events(month)
    LazyColumn(contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(when (java.time.LocalTime.now().hour) { in 5..11 -> "上午好"; in 12..17 -> "下午好"; else -> "晚上好" }, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
            Box {
            Image(painterResource(R.drawable.rococo_garden), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("总资产", color = Color(0xFF382F36).copy(alpha = .8f)); Text(display(s.total()), color = Color(0xFF382F36), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                HorizontalDivider(color = Color(0xFF382F36).copy(alpha = .2f))
                Text("当前活期 ${display(s.cash())}   ·   定期 ${display(s.principal())}", color = Color(0xFF382F36), style = MaterialTheme.typography.bodySmall)
                if (s.reserved() > 0) {
                    Text("未来定期 ${display(s.futureDeposits())} · 未来转出 ${display(s.reserved() - s.futureDeposits())}", color = Color(0xFF382F36), style = MaterialTheme.typography.bodySmall)
                    Text("可用活期 ${display(s.availableCash())}", color = Color(0xFF382F36), fontWeight = FontWeight.SemiBold)
                }
                Text(if (s.settings.includeAccruedInterest) "已计入按日计息产品的应计收益" else "预计收益单列，不计入当前资产", color = Color(0xFF382F36).copy(alpha = .75f), style = MaterialTheme.typography.labelSmall)
            }
            }
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { StatCard("预计总利息", s.expectedInterest(), Modifier.weight(1f)); StatCard("今日预计收入", s.dailyInterest(LocalDate.now()).fold(0L) { n, item -> add(n, item.second) }, Modifier.weight(1f)) } }
        item { Section("资产分布"); Distribution(s) }
        if (s.accounts.isEmpty()) item { Empty("从第一个账户开始", "创建活期账户后，即可添加定期与自动月存。"); Button(onClick = create, modifier = Modifier.fillMaxWidth()) { Text("创建活期账户") } }
        else item { Section("活期账户"); Card { Column(Modifier.padding(16.dp)) { s.accounts.forEach { a ->
            ValueRow(a.name + if (a.includeInTotal) "" else "（不计总额）", a.balance)
            if (s.reserved(a.id) > 0) {
                ValueRow("未来定期", s.futureDeposits(a.id)); ValueRow("未来转出", s.reserved(a.id) - s.futureDeposits(a.id))
                ValueRow("可用活期", Math.subtractExact(a.balance, s.reserved(a.id)))
            }
        }; TextButton(onClick = cash) { Text("记录活期变动") } } } }
        val plans = s.transactions.filter { !it.applied && it.amount < 0 }.distinctBy { it.relatedDepositId ?: it.transferGroupId ?: it.id }.sortedBy { it.date }
        if (plans.isNotEmpty()) {
            item { Section("未来定期 / 转出计划", "仅预留，不提前扣款；到日打开应用自动执行。") }
            items(plans, key = { "plan:${it.id}" }) { t -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text(s.deposits.find { it.id == t.relatedDepositId }?.let { "未来定期 · ${it.name}" } ?: t.title)
                Text("${t.date} · ${display(Math.negateExact(t.amount))}")
                TextButton(onClick = { cancelPlan(t.id) }) { Text("取消计划 / 释放预留") }
            } } }
        }
        item { Section("即将到期", "按到期日期排列 · 打开应用时自动结算") }
        val upcoming = s.active().filter { !it.archived }.sortedBy { it.endDate }.take(6)
        if (upcoming.isEmpty()) item { Empty("暂无待到期产品", "添加定期后，在这里查看本金、利率和回款时间。") }
        items(upcoming, key = { it.id }) { DepositCard(it) { detail(it.id) } }
        item { Section("本月概览", "${month.year}年${month.monthValue}月 · 仅显示截至今天的实际记录")
            Card { Column(Modifier.padding(16.dp)) {
                ValueRow("自动月存（已入账）", events.filter { it.kind == "MONTHLY_DEPOSIT" }.sumOf { it.cents })
                ValueRow("到期回款（实际本息）", events.filter { it.kind == "FIXED_DEPOSIT_MATURE" }.sumOf { it.cents })
                ValueRow("定期存入（内部转移）", -events.filter { it.kind in setOf("FIXED_DEPOSIT_CREATE", "ROLLOVER") }.sumOf { it.cents })
                val actualDelta = s.transactions.filter { it.applied && it.date in month.atDay(1).toString()..today() }.sumOf { it.amount }
                val allCash = s.accounts.sumOf { it.balance }
                ValueRow("期初活期（全部账户）", allCash - actualDelta); ValueRow("当前活期（全部账户）", allCash)
            } }
        }
    }
}

@Composable private fun Distribution(s: Ledger) {
    val buckets = listOf("活期" to s.cash().coerceAtLeast(0), "定期 / 存单" to s.active().filter { s.included(it) && it.type in setOf("定期存款", "大额存单") }.sumOf { it.principal },
        "理财 / 其他" to s.active().filter { s.included(it) && it.type !in setOf("定期存款", "大额存单") }.sumOf { it.principal })
    val colors = listOf(MaterialTheme.colorScheme.primary, Color(0xFFB5C7A5), Color(0xFFD6B087))
    val total = buckets.sumOf { it.second }.coerceAtLeast(1)
    val track = MaterialTheme.colorScheme.outlineVariant
    Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Canvas(Modifier.size(92.dp)) {
            drawArc(track, 0f, 360f, false, style = Stroke(16.dp.toPx()))
            var angle = -90f
            buckets.forEachIndexed { i, (_, value) -> val sweep = BigDecimal(value).multiply(BigDecimal(360)).divide(BigDecimal(total), 5, RoundingMode.HALF_UP).toFloat(); drawArc(colors[i], angle + 2f, (sweep - 4f).coerceAtLeast(0f), false, style = Stroke(16.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)); angle += sweep }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { buckets.forEachIndexed { i, (label, value) ->
            val percent = BigDecimal(value).multiply(BigDecimal(100)).divide(BigDecimal(total), 1, RoundingMode.HALF_UP)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text("●", color = colors[i]); Text("$label   ${if (LocalHidden.current) "••" else "$percent%"}", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium) }
        } }
    } }
}

@Composable private fun DepositCard(d: Deposit, click: () -> Unit) {
    val days = d.days()
    val color = when { d.status != "ACTIVE" -> MaterialTheme.colorScheme.onSurfaceVariant; days <= 7 -> Color(0xFFAA713D); days <= 30 -> MaterialTheme.colorScheme.secondary; else -> MaterialTheme.colorScheme.primary }
    Card(Modifier.fillMaxWidth().clickable(onClick = click), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(d.institution.ifBlank { d.type }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(d.statusLabel(), color = color, style = MaterialTheme.typography.labelMedium) }
        Text(d.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(display(d.principal), style = MaterialTheme.typography.titleLarge); Text("${d.annualRateText}%", color = MaterialTheme.colorScheme.primary) }
        Text("到期 ${d.endDate}${if (d.status == "ACTIVE") " · 还有${days.coerceAtLeast(0)}天" else ""}", style = MaterialTheme.typography.bodySmall, color = color)
        Text("${if (d.actualMaturityAmount == null) "预计本息" else "实际到账"} ${display(d.actualMaturityAmount ?: d.maturity())}", style = MaterialTheme.typography.bodyMedium)
    } }
}

@Composable private fun DepositsScreen(s: Ledger, detail: (String) -> Unit, accountEdit: (Account) -> Unit, ruleEdit: (MonthlyRule) -> Unit,
    ruleToggle: (MonthlyRule) -> Unit, ruleDelete: (MonthlyRule) -> Unit, transfer: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }; var search by remember { mutableStateOf("") }; var filter by remember { mutableStateOf("ALL") }; var sort by remember { mutableStateOf("END") }
    var deleteRule by remember { mutableStateOf<MonthlyRule?>(null) }
    Column {
        PrimaryTabRow(selectedTabIndex = tab) { listOf("定期资产", "活期账户", "收入 / 月存").forEachIndexed { i, title -> Tab(selected = i == tab, onClick = { tab = i }, text = { Text(title) }) } }
        LazyColumn(contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (tab) {
                0 -> {
                    item { OutlinedTextField(search, { search = it }, label = { Text("搜索名称或银行") }, leadingIcon = { Icon(Icons.Outlined.Search, "搜索") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) { ChoiceField("状态", filter, listOf("ALL" to "全部", "ACTIVE" to "进行中", "PLANNED" to "未来定期", "SOON" to "30天内到期", "MATURED" to "已到期", "ROLLED" to "已转存", "EARLY" to "提前支取", "ARCHIVED" to "已归档")) { filter = it } }
                        Box(Modifier.weight(1f)) { ChoiceField("排序", sort, listOf("END" to "到期日期", "PRINCIPAL" to "本金从高到低", "RATE" to "利率从高到低", "CREATED" to "最近创建")) { sort = it } }
                    } }
                    val data = s.deposits.filter { d ->
                        (if (filter == "ARCHIVED") d.archived else !d.archived) && (d.name.contains(search, true) || d.institution.contains(search, true)) &&
                            when (filter) { "ALL", "ARCHIVED" -> true; "SOON" -> d.status == "ACTIVE" && d.days() in 0..30; else -> d.status == filter }
                    }.let { data -> when (sort) { "PRINCIPAL" -> data.sortedByDescending { it.principal }; "RATE" -> data.sortedByDescending { BigDecimal(it.annualRateText) }; "CREATED" -> data.sortedByDescending { it.createdAt }; else -> data.sortedBy { it.endDate } } }
                    if (data.isEmpty()) item { Empty("暂无符合条件的存款", "点击右下角 + 添加定期或固定收益产品。") }
                    items(data, key = { it.id }) { DepositCard(it) { detail(it.id) } }
                }
                1 -> {
                    item { Text("账户之间转账不改变净资产。关闭计入总额时，该账户及其来源定期会一起排除。", style = MaterialTheme.typography.bodySmall); OutlinedButton(onClick = transfer, enabled = s.accounts.size >= 2) { Text("账户间转账") } }
                    if (s.accounts.isEmpty()) item { Empty("还没有活期账户", "通过 + 创建账户，并填写现有余额。") }
                    items(s.accounts, key = { it.id }) { a -> Card(Modifier.fillMaxWidth().clickable { accountEdit(a) }) { Column(Modifier.padding(20.dp)) { Text(a.name, fontWeight = FontWeight.SemiBold); Text(display(a.balance), style = MaterialTheme.typography.headlineSmall); if (s.reserved(a.id) > 0) { ValueRow("未来定期", s.futureDeposits(a.id)); ValueRow("未来转出", s.reserved(a.id) - s.futureDeposits(a.id)); ValueRow("可用活期", Math.subtractExact(a.balance, s.reserved(a.id))) }; Text(if (a.includeInTotal) "计入总资产" else "不计入总资产", style = MaterialTheme.typography.labelSmall); if (a.note.isNotBlank()) Text(a.note) } } }
                }
                2 -> {
                    item { Text("自动月存只补记本地资金，不连接银行。停用期间不入账，重新启用后会补记规则日期内尚未执行的月份。", style = MaterialTheme.typography.bodySmall) }
                    if (s.monthlyRules.isEmpty()) item { Empty("为储蓄建立节奏", "添加每月固定存款规则，应用会在打开时自动补记。") }
                    items(s.monthlyRules, key = { it.id }) { r -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text(r.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Switch(r.enabled, { ruleToggle(r) }) }
                        Text("每月${r.dayOfMonth}日 · ${display(r.amount)}"); Text("${s.accounts.find { it.id == r.targetAccountId }?.name} · ${r.startDate} 至 ${r.endDate ?: "长期"}", style = MaterialTheme.typography.bodySmall)
                        PictureGallery(r.imagesJson)
                        Text("上次执行：${r.lastProcessedDate ?: "尚未执行"}", style = MaterialTheme.typography.labelSmall)
                        Row { TextButton(onClick = { ruleEdit(r) }) { Text("编辑") }; TextButton(onClick = { deleteRule = r }) { Text("删除规则") } }
                    } } }
                }
            }
        }
    }
    deleteRule?.let { r -> AlertDialog(onDismissRequest = { deleteRule = null }, title = { Text("删除自动月存规则？") }, text = { Text("删除「${r.name}」及其所有已生成的存款流水，并从账户扣回对应金额。如果资金已使用，账户可能出现负余额。") },
        confirmButton = { TextButton(onClick = { ruleDelete(r); deleteRule = null }) { Text("删除规则") } }, dismissButton = { TextButton(onClick = { deleteRule = null }) { Text("取消") } }) }
}

@Composable private fun DepositDetail(d: Deposit, s: Ledger, close: () -> Unit, edit: () -> Unit, early: () -> Unit, rollover: () -> Unit, archive: () -> Unit) {
    val totalDays = ChronoUnit.DAYS.between(LocalDate.parse(d.startDate), LocalDate.parse(d.endDate)).coerceAtLeast(1)
    val held = ChronoUnit.DAYS.between(LocalDate.parse(d.startDate), LocalDate.now()).coerceIn(0, totalDays)
    AlertDialog(onDismissRequest = close, title = { Text(d.name) }, text = {
        Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${d.institution} · ${d.type} · ${d.statusLabel()}"); ValueRow("本金", d.principal)
            Text("年利率 ${d.annualRateText}%"); Text("开始 ${d.startDate}\n到期 ${d.endDate}")
            LinearProgressIndicator(progress = { held.toFloat() / totalDays.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text("${if (d.status == "PLANNED") "尚未开始，${d.startDate} 执行扣款" else if (d.status == "ACTIVE") "剩余 ${d.days().coerceAtLeast(0)} 天" else "结算于 ${d.settledAt}"} · ${when (d.interestMode) { "MONTH" -> "按月计息 ${d.months} 个月"; "MANUAL" -> "手动到期金额"; else -> "实际天数 / 365" }}", style = MaterialTheme.typography.bodySmall)
            ValueRow("预计利息", d.interest()); ValueRow("预计本息", d.maturity())
            d.actualMaturityAmount?.let { ValueRow("实际到账", it); ValueRow("实际利息", it - d.principal) }
            Text("来源：${s.accounts.find { it.id == d.sourceAccountId }?.name}\n回款：${s.accounts.find { it.id == d.targetAccountId }?.name}", style = MaterialTheme.typography.bodySmall)
            PictureGallery(d.imagesJson)
            if (d.note.isNotBlank()) Text(d.note)
            Section("转存历史")
            val root = run { var cur = d; val seen = mutableSetOf<String>(); while (cur.parentDepositId != null && seen.add(cur.id)) { cur = s.deposits.find { it.id == cur.parentDepositId } ?: break }; cur }
            val chain = mutableListOf(root); var cur = root
            while (true) { val child = s.deposits.find { it.parentDepositId == cur.id } ?: break; if (child in chain) break; chain += child; cur = child }
            chain.forEach { node -> Text("${node.startDate}  ${display(node.principal)}  ${node.annualRateText}%\n${node.name} · ${node.statusLabel()}${node.actualMaturityAmount?.let { "\n↓ 到账 ${display(it)}" } ?: ""}", style = MaterialTheme.typography.bodySmall) }
            Row { TextButton(onClick = early, enabled = d.status == "ACTIVE") { Text("提前支取") }; TextButton(onClick = edit) { Text("编辑") }; TextButton(onClick = rollover, enabled = d.status == "MATURED") { Text("转存") } }
            if (d.status !in setOf("ACTIVE", "PLANNED") && !d.archived) TextButton(onClick = archive) { Text("归档（保留资金历史）") }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("关闭") } })
}

@Composable private fun CalendarScreen(s: Ledger, create: (String) -> Unit, delete: (String) -> Unit, edit: (String) -> Unit) {
    var mode by remember { mutableIntStateOf(0) }; var month by remember { mutableStateOf(YearMonth.now()) }; var selectedDay by remember { mutableIntStateOf(LocalDate.now().dayOfMonth) }
    var filter by remember { mutableStateOf("ALL") }
    Column {
        PrimaryTabRow(selectedTabIndex = mode) { listOf("月视图", "年视图", "全部流水").forEachIndexed { i, name -> Tab(mode == i, { mode = i }, text = { Text(name) }) } }
        LazyColumn(contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (mode < 2) item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { month = if (mode == 0) month.minusMonths(1) else month.minusYears(1); selectedDay = 1 }) { Icon(Icons.Outlined.ChevronLeft, "上一期") }
                Text(if (mode == 0) "${month.year}年${month.monthValue}月" else "${month.year}年", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { month = if (mode == 0) month.plusMonths(1) else month.plusYears(1); selectedDay = 1 }) { Icon(Icons.Outlined.ChevronRight, "下一期") }
            } }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("截至 ${today()} 的实际记录", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { create(if (mode == 0) month.atDay(selectedDay.coerceAtMost(month.lengthOfMonth())).toString() else if (mode == 1) LocalDate.of(month.year, 1, 1).toString() else today()) }) { Text("新增流水") }
            } }
            when (mode) {
                0 -> {
                    val events = s.events(month)
                    item { MonthGrid(month, selectedDay, events, s) { selectedDay = it }; Text("绿：月存   蓝：定期   橙：到期   紫：转存", style = MaterialTheme.typography.labelSmall) }
                    item { Section("${month.monthValue}月${selectedDay.coerceAtMost(month.lengthOfMonth())}日", "实际入账，可新增或删除") }
                    item {
                        val date = month.atDay(selectedDay.coerceAtMost(month.lengthOfMonth()))
                        if (date <= LocalDate.now()) Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            val earnings = s.dailyInterest(date)
                            ValueRow("当日预计利息", earnings.fold(0L) { total, pair -> add(total, pair.second) })
                            Text("估算收益，未作为入账流水；按月及手动收益按整个存期平均分摊。", style = MaterialTheme.typography.labelSmall)
                            earnings.forEach { (deposit, cents) -> ValueRow(deposit.name, cents) }
                        } }
                    }
                    val dayEvents = events.filter { LocalDate.parse(it.date).dayOfMonth == selectedDay.coerceAtMost(month.lengthOfMonth()) }
                    if (dayEvents.isEmpty()) item { Empty("这一天暂无事件", "选择日期后可新增流水；未来尚未发生的存款不会显示。") }
                    items(dayEvents, key = { it.id }) { EventCard(it, images = s.transactionPictures(s.transactions.first { t -> t.id == it.id }), delete = { delete(it.id) }, edit = { edit(it.id) }) }
                }
                1 -> {
                    val events = (1..12).flatMap { s.events(YearMonth.of(month.year, it)) }
                    val deposits = s.deposits.filter { LocalDate.parse(it.endDate).year == month.year && it.status in setOf("MATURED", "ROLLED") && it.endDate <= today() }
                    item { Card { Column(Modifier.padding(18.dp)) {
                        ValueRow("全年新增存款（已入账）", events.filter { it.kind in setOf("MONTHLY_DEPOSIT", "FIXED_INCOME", "MANUAL_INCOME") }.sumOf { it.cents })
                        ValueRow("全年到期本金（已入账）", deposits.sumOf { it.principal })
                        ValueRow("全年利息（已实现）", deposits.sumOf { (it.actualMaturityAmount ?: it.maturity()) - it.principal })
                        val actual = s.deposits.filter { it.settledAt?.let { date -> LocalDate.parse(date).year == month.year } == true }
                        ValueRow("已实现利息", actual.sumOf { (it.actualMaturityAmount ?: it.principal) - it.principal })
                        val weighted = if (s.principal() > 0) s.active().filter { s.included(it) }.fold(BigDecimal.ZERO) { n, d -> n + BigDecimal(d.principal).multiply(BigDecimal(d.annualRateText)) }.divide(BigDecimal(s.principal()), 4, RoundingMode.HALF_UP).toPlainString() else "0"
                        Text("当前本金加权平均年利率 $weighted%", style = MaterialTheme.typography.bodySmall)
                    } } }
                    items(events, key = { it.id }) { EventCard(it, images = s.transactionPictures(s.transactions.first { t -> t.id == it.id }), delete = { delete(it.id) }, edit = { edit(it.id) }) }
                    items((1..12).toList()) { m -> val e = s.events(YearMonth.of(month.year, m)); Card(Modifier.fillMaxWidth().clickable { month = YearMonth.of(month.year, m); selectedDay = 1; mode = 0 }) { Column(Modifier.padding(16.dp)) {
                        Text("${m}月", fontWeight = FontWeight.Bold); ValueRow("存入（已入账）", e.filter { it.kind in setOf("MONTHLY_DEPOSIT", "FIXED_INCOME", "MANUAL_INCOME") }.sumOf { it.cents }); ValueRow("到期本息（已入账）", e.filter { it.kind == "FIXED_DEPOSIT_MATURE" }.sumOf { it.cents })
                    } } }
                }
                2 -> {
                    item { ChoiceField("流水筛选", filter, listOf("ALL" to "全部", "FIXED_INCOME" to "固定收入", "MONTHLY_DEPOSIT" to "自动存款", "FIXED_DEPOSIT_CREATE" to "定期存入", "FIXED_DEPOSIT_MATURE" to "到期", "ROLLOVER" to "转存", "TRANSFER" to "账户转账", "ADJUSTMENT" to "手动调整", "FIXED_DEPOSIT_EARLY_WITHDRAW" to "提前支取", "MANUAL_INCOME" to "外部存入", "MANUAL_EXPENSE" to "外部支出")) { filter = it } }
                    val t = s.transactions.filter { it.applied && it.date <= today() && (filter == "ALL" || it.type == filter) }.sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.createdAt })
                    if (t.isEmpty()) item { Empty("还没有流水", "账户变动、定期和月存都会自动记录资金历史。") }
                    items(t, key = { it.id }) { EventCard(AssetEvent(it.date, it.title, it.amount, it.type, id = it.id), it.note, images = s.transactionPictures(it), delete = { delete(it.id) }, edit = { edit(it.id) }) }
                }
            }
        }
    }
}
private fun eventColor(kind: String): Color = when (kind) { "MONTHLY_DEPOSIT", "FIXED_INCOME", "MANUAL_INCOME" -> Color(0xFF579B76); "FIXED_DEPOSIT_MATURE", "FIXED_DEPOSIT_EARLY_WITHDRAW" -> Color(0xFFB78A53); "ROLLOVER" -> Color(0xFF977BB2); else -> Color(0xFF648FA8) }
@Composable private fun MonthGrid(month: YearMonth, selected: Int, events: List<AssetEvent>, ledger: Ledger, select: (Int) -> Unit) {
    val offset = month.atDay(1).dayOfWeek.value - 1
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row { listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelSmall) } }
        repeat((offset + month.lengthOfMonth() + 6) / 7) { week -> Row { repeat(7) { day ->
            val n = week * 7 + day - offset + 1
            Box(Modifier.weight(1f).height(70.dp), contentAlignment = Alignment.Center) {
                if (n in 1..month.lengthOfMonth()) Surface(color = if (n == selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxSize().clickable { select(n) }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(n.toString(), fontWeight = if (n == selected) FontWeight.Bold else FontWeight.Normal)
                        val date = month.atDay(n)
                        val interest = ledger.dailyInterest(date).fold(0L) { total, pair -> add(total, pair.second) }
                        if (date <= LocalDate.now() && interest != 0L) Text(if (LocalHidden.current) "••" else "≈${yuan(interest)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { events.filter { LocalDate.parse(it.date).dayOfMonth == n }.map { it.kind }.distinct().take(3).forEach { Text("•", color = eventColor(it), style = MaterialTheme.typography.labelSmall) } }
                    }
                }
            }
        } } }
    } }
}
@Composable private fun EventCard(e: AssetEvent, note: String = "", images: String = "[]", delete: (() -> Unit)? = null, edit: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("●", color = eventColor(e.kind)); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(e.title, fontWeight = FontWeight.Medium); Text("${e.date} · ${eventLabel(e.kind)}${if (e.forecast) " · 计划" else " · 已记账"}", style = MaterialTheme.typography.labelSmall); if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall); PictureGallery(images) }
        Column(horizontalAlignment = Alignment.End) { Text(display(e.cents), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium); if (edit != null) TextButton(onClick = edit) { Text("明细 / 截图") }; if (delete != null) IconButton(onClick = delete) { Icon(Icons.Outlined.DeleteOutline, "删除流水") } }
    } }
}

@Composable private fun SettingsScreen(s: Ledger, vm: AppViewModel, exportJson: () -> Unit, exportCsv: () -> Unit, importJson: () -> Unit, importCsv: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Section("外观与金额"); Card { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceField("主题", s.settings.theme, listOf("SYSTEM" to "跟随系统", "LIGHT" to "亮色模式", "DARK" to "深色模式")) { theme -> vm.act(success = "") { it.copy(settings = it.settings.copy(theme = theme)) } }
            Toggle("隐藏金额", s.settings.hideMoney) { value -> vm.act(success = "") { it.copy(settings = it.settings.copy(hideMoney = value)) } }
            Toggle("总资产计入按日应计收益", s.settings.includeAccruedInterest) { value -> vm.act(success = "") { it.copy(settings = it.settings.copy(includeAccruedInterest = value)) } }
            Text("默认不计预计收益。手动到期金额和按月产品不计入每日应计收益。默认货币：人民币 ¥", style = MaterialTheme.typography.bodySmall)
        } } }
        item { Section("数据管理", "完整恢复请使用 JSON；CSV 用于新增资产与表格分析（包含图片 Base64）")
            Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Button(onClick = exportJson, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("导出完整备份（JSON）") }
                OutlinedButton(onClick = importJson, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("导入完整备份 / 覆盖恢复") }
                OutlinedButton(onClick = exportCsv, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("导出 CSV（含图片完整备份）") }
                OutlinedButton(onClick = importCsv, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("导入 CSV（先预览）") }
                Text("完整 JSON 和本应用导出的 CSV 都可恢复全部记录与截图。旧版资产 CSV 导入会扣减来源账户本金，并自动处理已到期资产；历史已结算记录请用完整备份恢复。", style = MaterialTheme.typography.bodySmall)
                Text("上次完整备份：${s.settings.lastBackup ?: "尚未备份"}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp))
                if (ChronoUnit.DAYS.between(LocalDate.parse(s.settings.lastBackup ?: s.settings.firstUsed), LocalDate.now()) >= 30) Text("你已超过30天没有备份，建议导出一次完整备份。", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 8.dp))
            } }
        }
        item { Section("到期提醒"); Card { Column(Modifier.padding(18.dp)) {
            Toggle("打开应用时显示到期提示", s.settings.reminders) { value -> vm.act(success = "") { it.copy(settings = it.settings.copy(reminders = value)) } }
            val upcoming = s.active().filter { it.days() in 1..30 }.sortedBy { it.endDate }
            Text("本版使用应用内提醒，不申请通知权限或后台常驻。关闭应用时不会发送通知。", style = MaterialTheme.typography.bodySmall)
            if (s.settings.reminders) upcoming.forEach { Text("${it.name} · ${it.days()}天后到期", modifier = Modifier.padding(top = 8.dp)) }
        } } }
        item { Section("隐私与关于"); Card { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("存期 1.3.0", fontWeight = FontWeight.Bold)
            Text("本应用不需要账号。\n本应用不会上传您的任何财务数据。\n所有数据均保存在本机。\n本应用不声明互联网权限，不依赖 Google 服务。")
            Text("卸载应用会删除本地数据。完整备份包含敏感财务信息，请保存到可信位置，并定期备份。", color = MaterialTheme.colorScheme.secondary)
            Text("自动月存是记账计划，不是银行自动转账。预计利息以实际银行回款为准。", style = MaterialTheme.typography.bodySmall)
        } } }
    }
}
@Composable private fun Toggle(title: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, modifier = Modifier.weight(1f)); Switch(value, change) }
}
@Composable private fun CsvImportDialog(p: CsvPreview, busy: Boolean, close: () -> Unit, apply: (Boolean, Boolean) -> Unit) {
    var skip by remember { mutableStateOf(true) }; var negative by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(if (p.complete == null) "CSV 导入预览" else "CSV 完整备份恢复") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.complete != null) Text("此文件包含完整备份和图片，将覆盖本机账户、投资、规则和流水。请先确认已保存当前数据。")
            Text("共发现 ${p.rowCount} 条\n成功解析 ${p.deposits.size} 条\n错误 ${p.errors.size} 条\n疑似重复 ${p.duplicates.size} 条")
            p.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (p.complete == null) Toggle("跳过疑似重复", skip) { skip = it }; if (p.complete == null) Toggle("余额不足时仍然导入", negative) { negative = it }
            if (p.complete == null) Text("确认导入后会扣减来源账户本金；任何一行失败都会整体回滚。请先检查账户余额，必要时先调整为导入前的余额。", style = MaterialTheme.typography.bodySmall)
            p.deposits.take(50).forEach { Text("${it.name} · ${display(it.principal)}\n${it.startDate} → ${it.endDate}${if (it.id in p.duplicates) " · 疑似重复" else ""}", style = MaterialTheme.typography.bodySmall) }
            if (p.deposits.size > 50) Text("仅展示前50条，确认后会处理全部记录。")
        }
    }, confirmButton = { Button(enabled = !busy && p.errors.isEmpty() && (p.deposits.isNotEmpty() || p.complete != null), onClick = { apply(skip, negative) }) { Text(if (p.complete == null) "确认导入" else "覆盖恢复含图片备份") } }, dismissButton = { TextButton(enabled = !busy, onClick = close) { Text("取消") } })
}
