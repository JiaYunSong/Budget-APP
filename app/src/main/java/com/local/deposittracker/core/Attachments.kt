package com.local.deposittracker.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

@Serializable data class Picture(val name: String, val mime: String = "image/jpeg", val base64: String)
object Pictures {
    const val MAX_FILE = 48_000_000
    fun encode(items: List<Picture>): String = Json.encodeToString(items)
    fun decode(text: String): List<Picture> = Json.decodeFromString(text)
    fun validate(text: String) {
        val items = decode(text)
        require(items.size <= 3) { "每笔最多三张图片" }
        items.forEach {
            require(it.base64.length <= 1_400_000 && it.mime in listOf("image/jpeg", "image/png", "image/webp")) { "图片过大或类型不支持" }
            val b = Base64.getDecoder().decode(it.base64)
            require(b.size in 12..1_000_000) { "图片大小不支持" }
            val valid = when(it.mime) {
                "image/jpeg" -> b[0].toInt() and 255 == 255 && b[1].toInt() and 255 == 216
                "image/png" -> b.take(8) == listOf(137,80,78,71,13,10,26,10).map { n -> n.toByte() }
                else -> String(b,0,4, Charsets.US_ASCII) == "RIFF" && String(b,8,4,Charsets.US_ASCII) == "WEBP"
            }
            require(valid) { "图片内容与类型不符" }
        }
    }
    fun annotate(before: Ledger, after: Ledger, images: String): Ledger {
        validate(images)
        val oldRows = before.transactions.map { it.id }.toSet()
        val oldDeposits = before.deposits.map { it.id }.toSet()
        val oldRules = before.monthlyRules.map { it.id }.toSet()
        return after.copy(transactions = after.transactions.map { if (it.id !in oldRows && ((it.relatedDepositId == null && it.relatedRuleId == null) || it.relatedDepositId !in oldDeposits && it.relatedDepositId != null || it.relatedRuleId !in oldRules && it.relatedRuleId != null)) it.copy(imagesJson = images) else it },
            deposits = after.deposits.map { if (it.id !in oldDeposits) it.copy(imagesJson = images) else it },
            monthlyRules = after.monthlyRules.map { if (it.id !in oldRules) it.copy(imagesJson = images) else it }).also { result ->
            val all = result.deposits.map { it.imagesJson } + result.monthlyRules.map { it.imagesJson } + result.transactions.map { it.imagesJson }
            require(all.fold(0L) { n, json -> n + json.length } <= 16_000_000) { "图片总量超过16MB，请减少截图" }
        }
    }
}

fun Ledger.transactionPictures(t: Transaction): String = if (t.imagesJson != "[]") t.imagesJson else
    t.relatedRuleId?.let { id -> monthlyRules.find { it.id == id }?.imagesJson }
        ?: t.relatedDepositId?.let { id -> deposits.find { it.id == id }?.imagesJson } ?: "[]"
