package pro.sitescope.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

internal suspend fun Context.writeDocument(uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
    val stream = contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Документ недоступен для записи")
    stream.use { it.write(bytes) }
}
