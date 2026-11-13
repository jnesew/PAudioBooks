package io.github.jnesew.paudiobooks.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.library.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun BookCover(book: Book, modifier: Modifier) {
    val app = LocalContext.current.applicationContext as AudiobookApplication
    val coverPath = book.coverOverridePath ?: book.coverPath
    var bitmap by remember(coverPath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(coverPath) {
        bitmap = withContext(Dispatchers.IO) {
            coverPath?.let { app.audioStore.resolveCover(it) }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
        }
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ratio = bitmap?.let { it.width.toFloat() / it.height } ?: 1f
        val width = minOf(maxWidth, maxHeight * ratio)
        val frame = Modifier.width(width).height(width / ratio)
        Surface(frame, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
            if (bitmap != null) Image(bitmap!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                Text(book.displayTitle.take(2).uppercase(Locale.ROOT), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
