package com.litemusic.app.feature.playlist.importing

import com.litemusic.shared.model.Album
import com.litemusic.shared.model.Artist
import com.litemusic.shared.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistImportMatchingTest {
    @Test
    fun jsonMetadataKeepsAlbumAsMatchingEvidence() {
        assertEquals(
            listOf(ImportedSongQuery("晴天", "周杰伦", "叶惠美")),
            parseImportedText("""[{"name":"晴天","artist":"周杰伦","album":"叶惠美"}]"""),
        )
    }

    @Test
    fun jsonScreenshotMetadataKeepsOcrAlternativesForMatching() {
        assertEquals(
            listOf(
                ImportedSongQuery(
                    title = "心仅烟火（原曲：絆）",
                    artist = "陈壹千",
                    album = "心仅烟火",
                    titleAlternatives = listOf("心似烟火（原曲：絆）", "心似烟火"),
                    artistAlternatives = listOf("陈壹千", "陈壹千 -"),
                    albumAlternatives = listOf("心似烟火"),
                    metadataAlternatives = listOf(ImportedSongMetadataAlternative("陈壹千", "心似烟火")),
                    fromScreenshot = true,
                ),
            ),
            parseImportedText(
                """[{"title":"心仅烟火（原曲：絆）","artist":"陈壹千","album":"心仅烟火","titleAlternatives":["心似烟火（原曲：絆）","心似烟火"],"artistAlternatives":["陈壹千","陈壹千 -"],"albumAlternatives":["心似烟火"],"metadataAlternatives":[{"artist":"陈壹千","album":"心似烟火"}],"fromScreenshot":true}]""",
            ),
        )
    }

    @Test
    fun promoAnnotationMatchesTheOriginalRecording() {
        val match = matchImportedSong(
            ImportedSongQuery("晴天（电视剧《青春》原曲）", "周杰伦"),
            listOf(song(1, "晴天", "周杰伦", "叶惠美")),
        )

        assertTrue(match.selectedByDefault)
        assertEquals(1L, match.song?.id)
    }

    @Test
    fun coverAndLiveVersionsAreNeverSelectedAsTheOriginal() {
        val cover = matchImportedSong(
            ImportedSongQuery("晴天", "周杰伦"),
            listOf(song(1, "晴天 (Cover)", "周杰伦", "翻唱集")),
        )
        val live = matchImportedSong(
            ImportedSongQuery("晴天", "周杰伦"),
            listOf(song(2, "晴天 (Live)", "周杰伦", "现场")),
        )

        assertFalse(cover.selectedByDefault)
        assertFalse(live.selectedByDefault)
    }

    @Test
    fun sameTitleAndArtistOnDifferentAlbumsStaysAmbiguousWithoutAlbumEvidence() {
        val match = matchImportedSong(
            ImportedSongQuery("晴天", "周杰伦"),
            listOf(
                song(1, "晴天", "周杰伦", "叶惠美"),
                song(2, "晴天", "周杰伦", "演唱会精选"),
            ),
        )

        assertFalse(match.selectedByDefault)
        assertNull(match.song)
    }

    @Test
    fun albumEvidenceSelectsTheMatchingRecording() {
        val match = matchImportedSong(
            ImportedSongQuery("晴天", "周杰伦", "叶惠美"),
            listOf(
                song(1, "晴天", "周杰伦", "叶惠美"),
                song(2, "晴天", "周杰伦", "演唱会精选"),
            ),
        )

        assertTrue(match.selectedByDefault)
        assertEquals(1L, match.song?.id)
    }

    @Test
    fun shortTitleDoesNotContainMatchAWhollyDifferentSong() {
        val match = matchImportedSong(
            ImportedSongQuery("晴", "周杰伦"),
            listOf(song(1, "晴天", "周杰伦", "叶惠美")),
        )

        assertNull(match.song)
        assertFalse(match.selectedByDefault)
    }

    @Test
    fun screenshotAlternativesCrossValidateTitleArtistAndAlbum() {
        val match = matchImportedSong(
            ImportedSongQuery(
                title = "心仅烟火（原曲：絆）",
                artist = "陈壹千",
                album = "心仅烟火",
                titleAlternatives = listOf("心似烟火（原曲：絆）", "心似烟火"),
                artistAlternatives = listOf("陈壹千"),
                albumAlternatives = listOf("心似烟火"),
                metadataAlternatives = listOf(ImportedSongMetadataAlternative("陈壹千", "心似烟火")),
                fromScreenshot = true,
            ),
            listOf(song(1, "心似烟火", "陈壹千", "心似烟火")),
        )

        assertTrue(match.selectedByDefault)
        assertEquals(1L, match.song?.id)
    }

    @Test
    fun screenshotTruncatedAlbumCanBeTheSecondEvidenceForTheUniqueRecording() {
        val match = matchImportedSong(
            ImportedSongQuery(
                title = "拼接马邦",
                artist = "Cyo/见过夏天P/乌托邦P",
                album = "反乌托邦·…",
                titleAlternatives = listOf("拼接马邦"),
                artistAlternatives = listOf("Cyo", "见过夏天P", "乌托邦P"),
                albumAlternatives = listOf("反乌托邦·"),
                fromScreenshot = true,
            ),
            listOf(song(1, "拼接马邦", "Cyo", "反乌托邦·2024")),
        )

        assertTrue(match.selectedByDefault)
        assertEquals(1L, match.song?.id)
    }

    @Test
    fun screenshotSplitArtistsCanConfirmAUniqueRecording() {
        val match = matchImportedSong(
            ImportedSongQuery(
                title = "月亮不会奔你而来",
                artist = "凯瑟喵/chat:chat",
                titleAlternatives = listOf("月亮不会奔你而来"),
                artistAlternatives = listOf("凯瑟喵", "chat:chat"),
                fromScreenshot = true,
            ),
            listOf(song(1, "月亮不会奔你而来", "凯瑟喵", "月亮不会奔你而来")),
        )

        assertTrue(match.selectedByDefault)
        assertEquals(1L, match.song?.id)
    }

    @Test
    fun screenshotArtistAliasCanConfirmAUniqueRecording() {
        val match = matchImportedSong(
            ImportedSongQuery(
                title = "偏偏要",
                artist = "庄东茹(豆芽鱼)",
                album = "偏偏要",
                artistAlternatives = listOf("豆芽鱼"),
                fromScreenshot = true,
            ),
            listOf(song(1, "偏偏要", "庄东茹", "偏偏要", artistAliases = listOf("豆芽鱼"))),
        )

        assertTrue(match.selectedByDefault)
        assertEquals(1L, match.song?.id)
    }

    @Test
    fun parenthesizedScreenshotArtistAliasMatchesTheCanonicalArtist() {
        val match = matchImportedSong(
            ImportedSongQuery("偏偏要", "庄东茹(豆芽鱼)", "偏偏要", fromScreenshot = true),
            listOf(song(1, "偏偏要", "庄东茹", "偏偏要")),
        )

        assertTrue(match.selectedByDefault)
        assertEquals(1L, match.song?.id)
    }

    @Test
    fun screenshotTitleEvidenceAloneNeverAutoSelects() {
        val match = matchImportedSong(
            ImportedSongQuery(
                title = "追",
                titleAlternatives = listOf("追"),
                fromScreenshot = true,
            ),
            listOf(song(1, "追", "陈壹千", "追")),
        )

        assertFalse(match.selectedByDefault)
    }

    @Test
    fun screenshotSearchUsesCleanTitleAndFirstArtistBeforeBoundedFallbacks() {
        assertEquals(
            listOf("阿拉斯新加海湾 蓝心羽", "阿拉斯新加海湾", "阿拉斯加海湾 蓝心羽", "阿拉斯加海湾",
                "阿拉斯新加海湾 其他人", "阿拉斯加海湾 其他人"),
            importSearchKeywords(
                ImportedSongQuery(
                    title = "阿拉斯新加海湾",
                    artist = "蓝心羽/其他人",
                    titleAlternatives = listOf("阿拉斯加海湾"),
                    fromScreenshot = true,
                ),
            ),
        )
    }

    @Test
    fun nonPromotionalParenthesesAndExplicitVersionAreNotDiscarded() {
        assertEquals("晴天（Live）", importSearchBaseTitle("晴天（Live）"))

        val match = matchImportedSong(
            ImportedSongQuery("晴天（Live）", "周杰伦", titleAlternatives = listOf("晴天"), fromScreenshot = true),
            listOf(song(1, "晴天", "周杰伦", "现场")),
        )

        assertFalse(match.selectedByDefault)
    }

    @Test
    fun pairedMetadataAlternativesCannotCrossCombineArtistAndAlbum() {
        val match = matchImportedSong(
            ImportedSongQuery(
                title = "同名歌",
                artist = "歌手甲",
                album = "专辑甲",
                artistAlternatives = listOf("歌手乙"),
                albumAlternatives = listOf("专辑乙"),
                metadataAlternatives = listOf(ImportedSongMetadataAlternative("歌手乙", "专辑乙")),
                fromScreenshot = true,
            ),
            listOf(song(1, "同名歌", "歌手乙", "专辑甲")),
        )

        assertFalse(match.selectedByDefault)
    }

    @Test
    fun versionInAnyTitleAlternativeRejectsAnIncompatibleCandidate() {
        val match = matchImportedSong(
            ImportedSongQuery(
                title = "晴天",
                artist = "周杰伦",
                titleAlternatives = listOf("晴天（Live）"),
                fromScreenshot = true,
            ),
            listOf(song(1, "晴天", "周杰伦", "叶惠美")),
        )

        assertFalse(match.selectedByDefault)
    }

    @Test
    fun coverAliasAndOfficialCoverTypeConfirmTheRequestedCover() {
        val match = matchImportedSong(
            ImportedSongQuery("IF YOU（温柔女声cover）", "风雪夜归人", "IF YOU", fromScreenshot = true),
            listOf(song(2615449509, "IF YOU", "风雪夜归人", "IF YOU", originCoverType = 2).copy(alia = listOf("温柔女声cover"))),
        )

        assertTrue(match.selectedByDefault)
    }

    @Test
    fun unspecifiedOriginStillAllowsUniqueExactMetadataWithoutVersionConflict() {
        val match = matchImportedSong(
            ImportedSongQuery("晴天", "周杰伦", "叶惠美", fromScreenshot = true),
            listOf(song(1, "晴天", "周杰伦", "叶惠美", originCoverType = 0)),
        )

        assertTrue(match.selectedByDefault)
    }

    @Test
    fun unspecifiedRecordingCannotReplaceAnExplicitCover() {
        val match = matchImportedSong(ImportedSongQuery("IF YOU (温柔女声cover)", "风雪夜归人", "IF YOU", fromScreenshot = true),
            listOf(song(1, "IF YOU", "风雪夜归人", "IF YOU", originCoverType = 0)))
        assertFalse(match.selectedByDefault)
    }

    @Test fun truncatedAlbumWithOcrTrailingPeriodIsStillPairedEvidence() {
        val match = matchImportedSong(ImportedSongQuery("罗生门(Follow)", "歌手", "罗生门(Foll….", fromScreenshot = true),
            listOf(song(1, "罗生门", "歌手", "罗生门(Follow)")))
        assertTrue(match.selectedByDefault)
    }

    @Test fun shortTruncatedAlbumDoesNotDisambiguateMultipleRecordings() {
        val match = matchImportedSong(ImportedSongQuery("我叫长安你叫故里", "歌手", "我叫…", fromScreenshot = true),
            listOf(song(1, "我叫长安你叫故里", "歌手", "我叫长安你叫故里"),
                song(2, "我叫长安你叫故里", "歌手", "我叫长安你叫故里精选")))
        assertFalse(match.selectedByDefault)
    }

    @Test fun exactRequestedTitleTakesPriorityOverARecordingWithExtraAnnotations() {
        val match = matchImportedSong(ImportedSongQuery("偏偏要", "歌手", "专辑", fromScreenshot = true),
            listOf(song(2, "偏偏要（1.2x）", "歌手", "专辑"), song(1, "偏偏要", "歌手", "专辑")))
        assertTrue(match.selectedByDefault)
        assertEquals(1L, match.song?.id)
    }

    @Test fun unclosedDisplayAnnotationStillRequiresUniquePairedMetadata() {
        val match = matchImportedSong(ImportedSongQuery("拼接乌托邦(只因为你那渴望自由的…", "歌手", "反乌托邦…", fromScreenshot = true),
            listOf(song(1, "拼接乌托邦", "歌手", "反乌托邦·拼接版")))
        assertTrue(match.selectedByDefault)
    }

    @Test fun fullWidthCommaDoesNotMakeTheOriginalTitleDifferent() {
        val match = matchImportedSong(ImportedSongQuery("我叫长安,你叫故里", "歌手", "我叫…", fromScreenshot = true),
            listOf(song(2, "我叫长安,你叫故里", "歌手", ""), song(1, "我叫长安，你叫故里", "歌手", "我叫长安，你叫故里")))
        assertTrue(match.selectedByDefault)
        assertEquals(1L, match.song?.id)
    }

    @Test fun exactArtistAndAlbumIdentifyACoverWhoseDisplayTitleHasNoVersionLabel() {
        val match = matchImportedSong(ImportedSongQuery("阿拉斯加海湾", "蓝心羽", "阿拉斯加海湾", fromScreenshot = true),
            listOf(song(1500569811, "阿拉斯加海湾", "蓝心羽", "阿拉斯加海湾", originCoverType = 2),
                song(1441758913, "阿拉斯加海湾", "菲道尔", "A Letter.", originCoverType = 1)))
        assertTrue(match.selectedByDefault)
        assertEquals(1500569811L, match.song?.id)
    }

    @Test fun femaleVersionAliasIsNotAnExtraUnrequestedCoverLabel() {
        val match = matchImportedSong(ImportedSongQuery("致你(女声版)", "yihuik苡慧", "致你", fromScreenshot = true),
            listOf(song(1867217766, "致你", "yihuik苡慧", "致你", originCoverType = 2).copy(alia = listOf("女声版"))))
        assertTrue(match.selectedByDefault)
    }

    @Test
    fun tiedScreenshotCandidatesAreNotAutoSelectedEvenWhenAllFieldsMatch() {
        val match = matchImportedSong(
            ImportedSongQuery("晴天", "周杰伦", "叶惠美", fromScreenshot = true),
            listOf(
                song(1, "晴天", "周杰伦", "叶惠美"),
                song(2, "晴天", "周杰伦", "叶惠美"),
            ),
        )

        assertFalse(match.selectedByDefault)
        assertNull(match.song)
    }

    private fun song(
        id: Long,
        title: String,
        artist: String,
        album: String,
        artistAliases: List<String> = emptyList(),
        originCoverType: Int = 1,
    ) = Song(
        id = id,
        name = title,
        ar = listOf(Artist(name = artist, alias = artistAliases)),
        al = Album(name = album),
        originCoverType = originCoverType,
    )
}
