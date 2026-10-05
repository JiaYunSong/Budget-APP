package com.local.deposittracker.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.local.deposittracker.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Base64

@Composable fun AttachmentEditor(value: String, loadingChanged: (Boolean) -> Unit = {}, change: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val items = remember(value) { Pictures.decode(value) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            loading = true; loadingChanged(true)
            try {
                val picture = withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    require(options.outWidth > 0 && options.outHeight > 0) { "图片无法读取" }
                    var sample = 1
                    while (maxOf(options.outWidth, options.outHeight) / sample > 1600) sample *= 2
                    val bitmap = requireNotNull(resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }) { "图片无法读取" }
                    try {
                        val out = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        require(out.size() <= 1_000_000) { "图片仍超过1MB，请选择较小截图" }
                        Picture("截图-${System.currentTimeMillis()}.jpg", base64 = Base64.getEncoder().encodeToString(out.toByteArray()))
                    } finally { bitmap.recycle() }
                }
                change(Pictures.encode(items + picture)); error = null
            } catch(e: Exception) { error = userError(e) } finally { loading = false; loadingChanged(false) }
        }
    }
    Column {
        Text("截图 / 图片附件（最多3张）", style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { pick.launch("image/*") }, enabled = !loading && items.size < 3) { Text(if(loading) "正在读取图片…" else "添加截图") }
        items.forEachIndexed { i,p -> Row { Text(p.name, Modifier.weight(1f)); TextButton(onClick = { change(Pictures.encode(items.filterIndexed { n,_ -> n != i })) }) { Text("移除") } } }
        PictureGallery(value)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
private fun decodePicture(p: Picture, edge: Int): Bitmap? {
    val bytes = Base64.getDecoder().decode(p.base64)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth,bounds.outHeight) / sample > edge) sample *= 2
    return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize = sample })
}
@Composable fun PictureGallery(value: String) {
    val items = remember(value) { Pictures.decode(value) }
    var selected by remember { mutableStateOf<Picture?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { p ->
            val bitmap = remember(p.base64) { decodePicture(p,320) }
            if(bitmap != null) Image(bitmap.asImageBitmap(), "查看截图", Modifier.size(64.dp).clickable { selected = p })
        }
    }
    selected?.let { p -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("记账截图") }, text = {
        val bitmap = remember(p) { decodePicture(p,1600) }
        if(bitmap != null) Image(bitmap.asImageBitmap(), p.name, Modifier.fillMaxWidth().heightIn(max = 480.dp))
    }, confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } }) }
}
