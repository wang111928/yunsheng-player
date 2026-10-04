package com.litemusic.app.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.litemusic.data.db.LocalMetaDao
import com.litemusic.data.db.LocalMetaEntity
import com.litemusic.data.prefs.SettingsStore
import com.litemusic.lyric.LocalLrcLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 本地音乐：MediaStore 实时查询（零后台扫描服务，秒级响应）。
 * 最小时长过滤（< 30 秒自动排除铃声），按歌曲/歌手/专辑/文件夹浏览，
 * 同目录 .lrc 歌词，元数据编辑仅存本地偏好。
 */
class LocalMusicRepository(private val context: Context) {

    data class LocalSong(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val path: String,
        val size: Long,
        val folder: String,
        val dateAdded: Long,
    )

    suspend fun query(settings: SettingsStore): List<LocalSong> = withContext(Dispatchers.IO) {
        val minSec = settings.minLocalSec.first()
        val minMs = minSec * 1000L
        val songs = mutableListOf<LocalSong>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " + MediaStore.Audio.Media.DURATION + " >= ?",
            arrayOf(minMs.toString()),
            MediaStore.Audio.Media.DATE_ADDED + " DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val path = c.getString(dataCol) ?: continue
                songs += LocalSong(
                    id = c.getLong(idCol),
                    title = c.getString(titleCol) ?: "未知歌曲",
                    artist = c.getString(artistCol) ?: "未知歌手",
                    album = c.getString(albumCol) ?: "未知专辑",
                    durationMs = c.getLong(durCol),
                    path = path,
                    size = c.getLong(sizeCol),
                    folder = path.substringBeforeLast('/', "未知文件夹"),
                    dateAdded = c.getLong(addedCol) * 1000,
                )
            }
        }
        songs
    }

    suspend fun lyric(song: LocalSong): String? = withContext(Dispatchers.IO) {
        LocalLrcLoader.load(song.path, song.title)
    }

    suspend fun updateMeta(mediaId: Long, title: String?, artist: String?, album: String?) {
        AppDatabaseHolder.instance?.localMetaDao()?.upsert(
            LocalMetaEntity(
                mediaId = mediaId,
                editedTitle = title,
                editedArtist = artist,
                editedAlbum = album,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    companion object {
        fun contentUri(id: Long) = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    }
}
