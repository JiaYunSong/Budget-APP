package com.local.deposittracker

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.deposittracker.core.*
import com.local.deposittracker.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = LedgerRepository(LedgerDatabase.open(app))
    val state = repo.state
    var ready by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var backupPreview by mutableStateOf<Backup?>(null)
    var csvPreview by mutableStateOf<CsvPreview?>(null)
    init {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { repo.load(); repo.update { Engine.settle(it, java.time.LocalDate.now()) } }; ready = true }
            catch (e: Exception) { message = "无法加载本地数据：${userError(e)}。请勿清除应用数据。" }
        }
    }
    fun act(success: String = "已保存", done: () -> Unit = {}, operation: (Ledger) -> Ledger) {
        if (busy || !ready) return
        viewModelScope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { repo.update(operation) }; message = success; done() }
            catch (e: Exception) { message = userError(e) }
            finally { busy = false }
        }
    }
    fun settle() { if (ready && !busy) act(success = "", operation = { Engine.settle(it, java.time.LocalDate.now()) }) }
    fun export(uri: Uri, csv: Boolean) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val s = repo.snapshot()
                    val text = if (csv) CsvCodec.export(s) else BackupCodec.export(s.copy(settings = s.settings.copy(lastBackup = today())))
                    val resolver = getApplication<Application>().contentResolver
                    requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法写入所选文件" }.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                    if (!csv) repo.update { it.copy(settings = it.settings.copy(lastBackup = today())) }
                }
                message = if (csv) "CSV 已导出（UTF-8 BOM）" else "完整备份已导出，请妥善保存"
            } catch (e: Exception) { message = "导出失败：${userError(e)}" } finally { busy = false }
        }
    }
    fun preview(uri: Uri, csv: Boolean) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try {
                val data = withContext(Dispatchers.IO) {
                    requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)) { "无法读取文件" }.use {
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = it.read(buffer); if (count < 0) break
                            require(output.size() + count <= 10_000_000) { "文件超过 10MB" }
                            output.write(buffer, 0, count)
                        }
                        output.toString("UTF-8")
                    }
                }
                withContext(Dispatchers.Default) {
                    if (csv) csvPreview = CsvCodec.preview(data, state.value) else backupPreview = BackupCodec.parse(data)
                }
            } catch (e: Exception) { message = "导入检查失败：${userError(e)}" } finally { busy = false }
        }
    }
}
