package com.local.deposittracker.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.local.deposittracker.core.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class PictureTest {
    @Test fun realPictureSurvivesRoomJsonAndCompleteCsv() = runTest {
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(180,100,140))
        val output = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        val images = Pictures.encode(listOf(Picture("截图.png", "image/png", Base64.getEncoder().encodeToString(output.toByteArray()))))
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).build()
        try {
            val repo = LedgerRepository(db)
            repo.update { Engine.createAccount(it, "图片账户", 0, true, "") }
            repo.update { before -> Pictures.annotate(before, Engine.change(before, before.accounts.single().id, -100, "MANUAL_EXPENSE", today(), "截图测试"), images) }
            val snapshot = repo.snapshot()
            val json = BackupCodec.parse(BackupCodec.export(snapshot)).ledger()
            val csv = CsvCodec.import(Ledger(), CsvCodec.preview(CsvCodec.export(snapshot, complete=true), Ledger()), true, true)
            assertEquals(snapshot,json); assertEquals(snapshot,csv)
            val picture = Pictures.decode(csv.transactions.first { it.type == "MANUAL_EXPENSE" }.imagesJson).single()
            val bytes = Base64.getDecoder().decode(picture.base64)
            val restored = requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
            assertEquals(24,restored.width); assertEquals(16,restored.height); restored.recycle()
            repo.update { json }; assertEquals(snapshot,repo.snapshot())
        } finally { db.close() }
    }
}
