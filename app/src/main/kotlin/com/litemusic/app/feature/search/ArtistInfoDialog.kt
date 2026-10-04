package com.litemusic.app.feature.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.litemusic.shared.model.Artist

/** Artist IDs are not user IDs. Open their real artist page from this preview. */
@Composable
fun ArtistInfoDialog(artist: Artist, onDismiss: () -> Unit, onOpenDetail: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(artist.name) },
        text = {
            Column {
                if (artist.picUrl.isNotBlank()) AsyncImage(
                    model = artist.picUrl,
                    contentDescription = artist.name,
                    modifier = Modifier.size(100.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
                if (artist.alias.isNotEmpty()) Text(artist.alias.joinToString(" · "))
            }
        },
        confirmButton = { TextButton(onClick = onOpenDetail) { Text("查看歌手主页") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
