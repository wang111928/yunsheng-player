package com.litemusic.app.feature.playlist.importing

import org.junit.Assert.*
import org.junit.Test

class PublicPlaylistReaderTest {
    @Test fun acceptsRealQqPlaylistIdsAndRejectsSongOrLookalikeDomains() {
        assertEquals(PublicPlaylistReference("qq", "1207922987"), publicPlaylistReference("https://y.qq.com/n/ryqq/playlist/1207922987"))
        assertEquals(PublicPlaylistReference("qq", "1207922987"), publicPlaylistReference("https://i.y.qq.com/n2/m/share/details/taoge.html?ADTAG=copy&disstid=1207922987"))
        assertNull(publicPlaylistReference("https://y.qq.com/n/ryqq/songDetail/001xyz"))
        assertNull(publicPlaylistReference("https://y.qq.com.evil.example/playlist/123"))
        assertFalse(allowedPublicMusicUrl("http://127.0.0.1/playlist/123"))
        assertFalse(allowedPublicMusicUrl("file:///sdcard/song.json"))
        assertFalse(allowedPublicMusicUrl("https://music.163.com@evil.example/playlist?id=1"))
    }

    @Test fun readsQqNamesAndArtistsAndDeduplicatesWithoutFabricatingAnEmptySuccess() {
        val source = parseQqPlaylistMetadata("""{"code":0,"cdlist":[{"dissname":"收藏","songlist":[{"songname":"晴天","singer":[{"name":"周杰伦"}]},{"title":"晴天","singer":[{"name":"周杰伦"}]},{"name":"夜曲","singer":[{"name":"周杰伦"},{"name":"合唱"}]}]}]}""")
        assertEquals("收藏", source?.title)
        assertEquals(listOf(ImportedSongQuery("晴天", "周杰伦"), ImportedSongQuery("夜曲", "周杰伦 / 合唱")), source?.songs)
        assertNull(parseQqPlaylistMetadata("""{"code":1000,"cdlist":[]}"""))
        assertNull(parseQqPlaylistMetadata("<html>登录</html>"))
    }

    @Test fun qqReadIsUnknownWithoutADeclaredSourceCountAndIncompleteWhenItDisagrees() {
        val unknown = parseQqPlaylistMetadata("""{"code":0,"cdlist":[{"dissname":"收藏","songlist":[{"songname":"晴天","singer":[{"name":"周杰伦"}]}]}]}""")
        assertEquals(ExternalPlaylistReadCompleteness.UNKNOWN, unknown?.readCompleteness)

        val incomplete = parseQqPlaylistMetadata("""{"code":0,"cdlist":[{"dissname":"收藏","songnum":2,"songlist":[{"songname":"晴天","singer":[{"name":"周杰伦"}]}]}]}""")
        assertEquals(2, incomplete?.totalCount)
        assertEquals(ExternalPlaylistReadCompleteness.INCOMPLETE, incomplete?.readCompleteness)
    }

    @Test fun readsStructuredMusicPlaylistsRatherThanUnrelatedWebPageTitles() {
        val html = """<script type="application/ld+json">{"@context":"https://schema.org","@type":"MusicPlaylist","name":"外部清单","track":[{"@type":"MusicRecording","name":"Yesterday","byArtist":{"@type":"MusicGroup","name":"The Beatles"}}]}</script>"""
        assertEquals(listOf(ImportedSongQuery("Yesterday", "The Beatles")), parseMusicPlaylistJsonLd(html)?.songs)
        assertNull(parseMusicPlaylistJsonLd("<title>QQ音乐 - 登录</title>"))
    }
}
