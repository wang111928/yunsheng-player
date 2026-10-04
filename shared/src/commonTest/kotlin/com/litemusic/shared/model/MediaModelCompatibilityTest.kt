package com.litemusic.shared.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaModelCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun legacySearchRetainsOfficialCoverAnnotationAndRecordingType() {
        val song = json.decodeFromString<Song>(
            """{"id":2615449509,"name":"IF YOU","alias":["温柔女声cover"],"originCoverType":2,"artists":[{"name":"风雪夜归人"}],"album":{"name":"IF YOU"}}""",
        )
        assertEquals(listOf("温柔女声cover"), song.alia)
        assertEquals(2, song.originCoverType)
        assertEquals("风雪夜归人", song.artistNames)
    }

    @Test
    fun modernSongAliasesAndTranslationRemainCompatible() {
        val song = json.decodeFromString<Song>("""{"name":"song","alia":["Live"],"tns":["译名"],"originCoverType":1}""")
        assertEquals(listOf("Live"), song.alia)
        assertEquals(listOf("译名"), song.tns)
        assertEquals(1, song.originCoverType)
    }

    @Test
    fun songUsesAlbumAndArtistsAliasesForItsCoverAndSubtitle() {
        val song = json.decodeFromString<Song>(
            """{"id":9,"name":"别名曲目","album":{"picUrl":"//p3.music.126.net/cover.jpg","name":"别名专辑"},"artists":[{"id":4,"name":"歌手"}]}""",
        )

        assertEquals("https://p3.music.126.net/cover.jpg", song.coverUrl)
        assertEquals("歌手", song.artistNames)
        assertEquals("别名专辑", song.albumName)
    }

    @Test
    fun artistDetailAndDiscoveryModelsDecodeRealArtistRows() {
        val detail = json.decodeFromString<ArtistDetailResponse>(
            """{"code":200,"artist":{"id":42,"name":"歌手","picUrl":"https://cover"},"hotSongs":[{"id":8,"name":"代表作","ar":[{"id":42,"name":"歌手"}]}]}""",
        )
        val discovery = json.decodeFromString<TopArtistsResponse>(
            """{"code":200,"artists":[{"id":42,"name":"歌手","picUrl":"https://cover"}]}""",
        )

        assertEquals(42L, detail.artist?.id)
        assertEquals("代表作", detail.hotSongs.single().name)
        assertEquals("歌手", discovery.artists.single().name)
    }

    @Test
    fun radioProgramReadsArrayProgramDescriptionWithoutFailingTheWholePage() {
        val response = json.decodeFromString<RadioProgramResponse>(
            """{"code":200,"programs":[{"id":7,"name":"节目","programDesc":[{"content":"第一段"},{"content":"第二段"}]}]}""",
        )

        assertEquals("第一段\n第二段", response.programs.single().text)
    }

    @Test
    fun radioProgramAcceptsStringAndNullProgramDescription() {
        val response = json.decodeFromString<RadioProgramResponse>(
            """{"code":200,"programs":[{"id":1,"programDesc":"字符串简介"},{"id":2,"programDesc":null}]}""",
        )

        assertEquals("字符串简介", response.programs[0].text)
        assertTrue(response.programs[1].text.isBlank())
    }

    @Test
    fun radioProgramRetainsMainTrackIdWhenTheListOmitsMainSong() {
        val response = json.decodeFromString<RadioProgramResponse>(
            """{"code":200,"programs":[{"id":7,"name":"节目","mainTrackId":12345,"mainSong":null}]}""",
        )

        assertEquals(12345L, response.programs.single().mainTrackId)
    }

    @Test
    fun radioProgramDecodesProgramAndEmbeddedStationArtwork() {
        val response = json.decodeFromString<RadioProgramResponse>(
            """{"code":200,"programs":[{"id":7,"coverUrl":"http://p3.music.126.net/program.jpg","radio":{"id":8,"picUrl":"http://p3.music.126.net/station.jpg"}}]}""",
        )

        assertEquals("https://p3.music.126.net/program.jpg", response.programs.single().artworkUrl)
    }

    @Test
    fun radioProgramPayloadNormalizesRichDescriptionBeforeTheResponseDecoderRuns() {
        val normalized = normalizeRadioProgramDescriptions(
            """{"code":200,"programs":[{"id":7,"programDesc":[{"type":1,"content":"第一段"},{"type":1,"content":"第二段"}]}]}""",
        )

        val response = json.decodeFromString<RadioProgramResponse>(normalized)

        assertEquals("第一段\n第二段", response.programs.single().programDesc)
    }
}
