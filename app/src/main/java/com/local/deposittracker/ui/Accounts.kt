package com.local.deposittracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.local.deposittracker.core.*

@Composable fun AccountManager(s: Ledger, busy: Boolean, close: () -> Unit, remove: (Set<String>, String?) -> Unit) {
    var ids by remember { mutableStateOf(setOf<String>()) }
    var target by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val remaining = s.accounts.filter { it.id !in ids }
    AlertDialog(onDismissRequest = close, title = { Text("管理账户") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            Text("勾选要删除的账户。可保留一个账户接收余额、投资、规则与历史流水，资产将按接收账户的统计设置计算。")
            s.accounts.forEach { a -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(a.id in ids, { checked -> ids = if (checked) ids + a.id else ids - a.id; if (target in ids) target = "" })
                Text(a.name + " · " + amount(a.balance))
            } }
            if (remaining.isNotEmpty()) ChoiceField("余额与历史记录转入", target, remaining.map { it.id to it.name }) { target = it }
            else if (ids.isNotEmpty()) Text("删除全部账户会同时清除全部投资、规则、流水和图片。", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { Button(enabled = !busy && ids.isNotEmpty() && (remaining.isEmpty() || target.isNotEmpty()), onClick = { confirm = true }) { Text("删除勾选账户") } }, dismissButton = { TextButton(onClick = close) { Text("关闭") } })
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("确认删除 ${ids.size} 个账户？") }, text = {
        Text(if (remaining.isEmpty()) "将清除全部财务记录与图片，无法撤销。" else "选中账户的余额（包括负余额）和全部关联记录将合并到保留账户，然后删除选中账户。")
    }, confirmButton = { TextButton(enabled = !busy, onClick = { remove(ids, target.takeIf { it.isNotEmpty() }); confirm = false }) { Text("确认删除") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}
