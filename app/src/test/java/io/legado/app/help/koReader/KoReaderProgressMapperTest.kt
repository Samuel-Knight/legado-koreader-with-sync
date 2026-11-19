package io.legado.app.help.koReader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KoReaderProgressMapperTest {

    @Test
    fun normalizesKoReaderServerAddresses() {
        assertEquals(
            "https://kosync.nickthesick.com",
            KoReaderDefaults.SERVER_URL,
        )
        assertEquals(
            "https://kosync.example.com",
            KoReaderProgressMapper.normalizeServerUrl("kosync.example.com", KoReaderDefaults.SERVER_URL),
        )
        assertEquals(
            "https://kosync.nickthesick.com",
            KoReaderProgressMapper.normalizeServerUrl("https://kosync.nickthesick.com/", "https://default.example"),
        )
        assertEquals(
            "https://kosync.nickthesick.com",
            KoReaderProgressMapper.normalizeServerUrl("", KoReaderDefaults.SERVER_URL),
        )
    }

    @Test
    fun acceptsFractionAndPercentageCloudValues() {
        assertEquals(
            0.68,
            KoReaderProgressMapper.normalizePercentage(0.68) ?: Double.NaN,
            0.0,
        )
        assertEquals(
            0.68,
            KoReaderProgressMapper.normalizePercentage(68.0) ?: Double.NaN,
            0.0,
        )
        assertEquals(
            0.1234,
            KoReaderProgressMapper.normalizePercentage(0.123456) ?: Double.NaN,
            0.0,
        )
        assertEquals(
            1.0,
            KoReaderProgressMapper.normalizePercentage(100.0) ?: Double.NaN,
            0.0,
        )
        assertEquals(
            0.0,
            KoReaderProgressMapper.normalizePercentage(-1.0) ?: Double.NaN,
            0.0,
        )
        assertNull(KoReaderProgressMapper.normalizePercentage(Double.NaN))
    }

    @Test
    fun partialMd5MatchesKOReaderSamplingAlgorithm() {
        val data = ByteArray(20_000) { index ->
            (index * 31 + index / 97).toByte()
        }
        assertEquals(
            "7460566a1c8997f679793899a6697d35",
            KoReaderProgressMapper.partialMd5(data.inputStream()),
        )
    }

    @Test
    fun mapsPercentageToChapterAndPosition() {
        val lengths = listOf(100, 100)
        assertEquals(0 to 50, KoReaderProgressMapper.mapPercentage(0.25, lengths))
        assertEquals(1 to 0, KoReaderProgressMapper.mapPercentage(0.5, lengths))
        assertEquals(1 to 50, KoReaderProgressMapper.mapPercentage(0.75, lengths))
        assertEquals(1 to 100, KoReaderProgressMapper.mapPercentage(1.0, lengths))
        assertNull(KoReaderProgressMapper.mapPercentage(0.5, emptyList()))
    }

    @Test
    fun weightsProgressByChapterLength() {
        val lengths = listOf(100, 300)
        assertEquals(
            0.625,
            KoReaderProgressMapper.percentage(1, 150, lengths),
            0.0,
        )
        assertEquals(0 to 50, KoReaderProgressMapper.mapPercentage(0.125, lengths))
        assertEquals(1 to 150, KoReaderProgressMapper.mapPercentage(0.625, lengths))
    }

    @Test
    fun createsLegadoPercentageFromChapterPosition() {
        assertEquals(
            0.25,
            KoReaderProgressMapper.percentage(0, 50, listOf(100, 100)),
            0.0,
        )
        assertEquals(
            1.0,
            KoReaderProgressMapper.percentage(1, 100, listOf(100, 100)),
            0.0,
        )
        assertEquals(
            0.75,
            KoReaderProgressMapper.percentage(
                chapterIndex = 1,
                chapterPosition = 0,
                chapterLengths = listOf(100, 100),
                chapterFractionOverride = 0.5,
            ),
            0.0,
        )
    }

    @Test
    fun parsesEpubXPointerFragment() {
        assertEquals(
            7,
            KoReaderProgressMapper.parseEpubFragmentNumber("/body/DocFragment[7]/body/p[2].12"),
        )
        assertEquals(7, KoReaderProgressMapper.parseEpubFragmentNumber("/body/DocFragment[7]"))
        assertNull(KoReaderProgressMapper.parseEpubFragmentNumber("12"))
    }

    @Test
    fun parsesEpubXPointerNodeAndCharacterOffset() {
        val pointer = KoReaderProgressMapper.parseEpubXPointer(
            "/body/DocFragment[23]/body/div[2]/p[4]/text().12"
        )
        assertEquals(23, pointer?.fragmentOrdinal)
        assertEquals("body/div[2]/p[4]/text()", pointer?.nodePath)
        assertEquals(12, pointer?.characterOffset)

        val elementPointer = KoReaderProgressMapper.parseEpubXPointer(
            "/body/DocFragment[3]/body/div/p[2]"
        )
        assertEquals("body/div/p[2]", elementPointer?.nodePath)
        assertEquals(0, elementPointer?.characterOffset)
        assertNull(KoReaderProgressMapper.parseEpubXPointer("/body/div/p[2]/text().1"))
    }

    @Test
    fun formatsChapterAnchorAsEpubXPointer() {
        assertEquals(
            "/body/DocFragment[7]/body/div[3]/h2",
            KoReaderProgressMapper.formatEpubElementXPointer(7, "/body/div[3]/h2"),
        )
        assertNull(KoReaderProgressMapper.formatEpubElementXPointer(0, "body/div"))
        assertNull(KoReaderProgressMapper.formatEpubElementXPointer(7, ""))
    }

    @Test
    fun formatsTextNodeAsEpubXPointer() {
        assertEquals(
            "/body/DocFragment[7]/body/div[3]/p[2]/text().12",
            KoReaderProgressMapper.formatEpubTextXPointer(
                fragmentOrdinal = 7,
                nodePath = "body/div[3]/p[2]/text()",
                characterOffset = 12,
            ),
        )
        assertNull(KoReaderProgressMapper.formatEpubTextXPointer(7, "body/p", -1))
    }

    @Test
    fun treatsTimestampAsAuthoritativeWhenRemotePositionIsAhead() {
        assertFalse(
            KoReaderProgressMapper.isRemoteNewer(
                localPercentage = 0.25,
                localTimestampSeconds = 2_000,
                remotePercentage = 0.5,
                remoteTimestampSeconds = 1_999.0,
                percentageEpsilon = 0.0001,
            )
        )
    }

    @Test
    fun acceptsNewerRemoteTimestampForEarlierRereadPosition() {
        assertTrue(
            KoReaderProgressMapper.isRemoteNewer(
                localPercentage = 0.6,
                localTimestampSeconds = 2_000,
                remotePercentage = 0.3,
                remoteTimestampSeconds = 2_001.0,
                percentageEpsilon = 0.0001,
            )
        )
    }

    @Test
    fun rejectsOlderRemotePositionEvenWhenPercentageDiffers() {
        assertFalse(
            KoReaderProgressMapper.isRemoteNewer(
                localPercentage = 0.6,
                localTimestampSeconds = 3_000,
                remotePercentage = 0.3,
                remoteTimestampSeconds = 2_000.0,
                percentageEpsilon = 0.0001,
            )
        )
    }

    @Test
    fun usesPositionOnlyWhenLegacyServerOmitsTimestamp() {
        assertTrue(
            KoReaderProgressMapper.isRemoteNewer(
                localPercentage = 0.3,
                localTimestampSeconds = 3_000,
                remotePercentage = 0.4,
                remoteTimestampSeconds = null,
                percentageEpsilon = 0.0001,
            )
        )
        assertFalse(
            KoReaderProgressMapper.isRemoteNewer(
                localPercentage = 0.4,
                localTimestampSeconds = 3_000,
                remotePercentage = 0.3,
                remoteTimestampSeconds = null,
                percentageEpsilon = 0.0001,
            )
        )
    }

    @Test
    fun choosesForwardAndBackwardStrategiesFromTimestamp() {
        val newer = KoReaderSyncPolicy.decide(
            localPercentage = 0.5,
            localTimestampSeconds = 2_000,
            remotePercentage = 0.4,
            remoteTimestampSeconds = 2_001.0,
            forwardStrategy = "silent",
            backwardStrategy = "prompt",
        )
        assertEquals(KoReaderSyncStrategy.SILENT, newer?.strategy)
        assertTrue(newer?.remoteIsNewer == true)

        val older = KoReaderSyncPolicy.decide(
            localPercentage = 0.5,
            localTimestampSeconds = 2_000,
            remotePercentage = 0.6,
            remoteTimestampSeconds = 1_999.0,
            forwardStrategy = "silent",
            backwardStrategy = "prompt",
        )
        assertEquals(KoReaderSyncStrategy.PROMPT, older?.strategy)
        assertFalse(older?.remoteIsNewer ?: true)
    }

    @Test
    fun skipsUnchangedAndDisabledAutoSyncStates() {
        assertNull(
            KoReaderSyncPolicy.decide(
                localPercentage = 0.5,
                localTimestampSeconds = 2_000,
                remotePercentage = 0.5,
                remoteTimestampSeconds = 2_001.0,
                forwardStrategy = "silent",
                backwardStrategy = "prompt",
            )
        )
        assertNull(
            KoReaderSyncPolicy.decide(
                localPercentage = 0.5,
                localTimestampSeconds = 2_000,
                remotePercentage = 0.4,
                remoteTimestampSeconds = 2_001.0,
                forwardStrategy = "disable",
                backwardStrategy = "prompt",
            )
        )
    }
}
