package io.legado.app.help.koReader

import java.io.InputStream
import java.security.MessageDigest

/** Pure protocol helpers shared by runtime code and JVM tests. */
object KoReaderProgressMapper {

    data class EpubXPointer(
        val fragmentOrdinal: Int,
        val nodePath: String,
        val characterOffset: Int,
    )

    fun normalizeServerUrl(value: String, defaultServer: String): String {
        val server = value.trim().trimEnd('/')
        if (server.isEmpty()) return defaultServer.trimEnd('/')
        return if (server.startsWith("http://", true) || server.startsWith("https://", true)) {
            server
        } else {
            "https://$server"
        }
    }

    /**
     * KOSync normally stores a fraction (0.68), while some compatible clients have
     * historically uploaded a percentage (68). Accept both representations so a
     * pull can never turn 68% into the end of the book.
     */
    fun normalizePercentage(value: Double): Double? {
        if (!value.isFinite()) return null
        val fraction = if (value > 1.0 && value <= 100.0) value / 100.0 else value
        return floorToFourDecimals(fraction.coerceIn(0.0, 1.0))
    }

    fun partialMd5(input: InputStream): String {
        val digest = MessageDigest.getInstance("MD5")
        val sample = ByteArray(SAMPLE_SIZE.toInt())
        var position = 0L
        for (index in -1..10) {
            val offset = if (index == -1) 0L else SAMPLE_SIZE shl (index * 2)
            if (!skipFully(input, offset - position)) break
            val count = readUpTo(input, sample)
            if (count == 0) break
            digest.update(sample, 0, count)
            position = offset + count
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun percentage(
        chapterIndex: Int,
        chapterPosition: Int,
        chapterLengths: List<Int>,
        chapterFractionOverride: Double? = null,
    ): Double {
        if (chapterLengths.isEmpty()) return 0.0
        val weights = chapterLengths.map { it.coerceAtLeast(0).toLong() }
        val totalLength = weights.sum()
        val index = chapterIndex.coerceIn(0, chapterLengths.lastIndex)
        if (totalLength == 0L) {
            val chapterFraction = 0.0
            return floorToFourDecimals(((index + chapterFraction) / chapterLengths.size).coerceIn(0.0, 1.0))
        }
        val chapterLength = weights[index]
        val chapterFraction = chapterFractionOverride
            ?.coerceIn(0.0, 1.0)
            ?: if (chapterLength > 0) {
                chapterPosition.coerceIn(0, chapterLengths[index]).toDouble() / chapterLength
            } else {
                0.0
            }
        val completedLength = weights.take(index).sum()
        return floorToFourDecimals(
            ((completedLength + chapterLength * chapterFraction) / totalLength).coerceIn(0.0, 1.0)
        )
    }

    fun mapPercentage(percentage: Double, chapterLengths: List<Int>): Pair<Int, Int>? {
        if (chapterLengths.isEmpty()) return null
        val bounded = percentage.coerceIn(0.0, 1.0)
        if (bounded == 1.0) {
            val last = chapterLengths.lastIndex
            return last to chapterLengths[last].coerceAtLeast(0)
        }
        val weights = chapterLengths.map { it.coerceAtLeast(0).toLong() }
        val totalLength = weights.sum()
        if (totalLength > 0L) {
            val target = bounded * totalLength
            var completedLength = 0.0
            weights.forEachIndexed { index, length ->
                if (target < completedLength + length) {
                    val position = (chapterLengths[index] * ((target - completedLength) / length))
                        .toInt()
                        .coerceAtLeast(0)
                    return index to position
                }
                completedLength += length
            }
            val last = chapterLengths.lastIndex
            return last to chapterLengths[last].coerceAtLeast(0)
        }
        val scaled = bounded * chapterLengths.size
        val index = scaled.toInt().coerceIn(0, chapterLengths.lastIndex)
        val fraction = scaled - index
        val position = (chapterLengths[index].coerceAtLeast(0) * fraction).toInt().coerceAtLeast(0)
        return index to position
    }

    fun parseEpubFragmentNumber(progress: String?): Int? {
        val match = EPUB_FRAGMENT.matchEntire(progress.orEmpty()) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    /**
     * Parses a CreNGINE XPointer such as
     * `/body/DocFragment[23]/body/div[2]/p[4]/text().12`.
     */
    fun parseEpubXPointer(progress: String?): EpubXPointer? {
        val value = progress?.trim().orEmpty()
        val marker = "DocFragment["
        val markerStart = value.indexOf(marker)
        if (markerStart < 0) return null
        val ordinalEnd = value.indexOf(']', markerStart + marker.length)
        if (ordinalEnd <= markerStart) return null
        val fragmentOrdinal = value.substring(markerStart + marker.length, ordinalEnd).toIntOrNull()
            ?.takeIf { it > 0 } ?: return null
        val remainder = value.substring(ordinalEnd + 1)
            .substringBefore('?')
            .trim('/')
        val offsetMatch = XPATH_OFFSET.matchEntire(remainder)
        val nodePath = (offsetMatch?.groupValues?.get(1) ?: remainder).trim('/')
        val characterOffset = offsetMatch?.groupValues?.get(2)?.toIntOrNull()
            ?.takeIf { it >= 0 } ?: 0
        if (nodePath.isBlank()) return null
        return EpubXPointer(fragmentOrdinal, nodePath, characterOffset)
    }

    fun formatEpubElementXPointer(fragmentOrdinal: Int, nodePath: String): String? {
        if (fragmentOrdinal <= 0) return null
        val normalizedPath = nodePath.trim('/')
        if (normalizedPath.isBlank()) return null
        return "/body/DocFragment[$fragmentOrdinal]/$normalizedPath"
    }

    fun formatEpubTextXPointer(
        fragmentOrdinal: Int,
        nodePath: String,
        characterOffset: Int,
    ): String? {
        if (fragmentOrdinal <= 0 || characterOffset < 0) return null
        val normalizedPath = nodePath.trim('/')
        if (normalizedPath.isBlank()) return null
        return "/body/DocFragment[$fragmentOrdinal]/$normalizedPath.$characterOffset"
    }

    fun parseLeadingInt(value: String?): Int {
        return value?.let { FIRST_INT.find(it)?.value?.toIntOrNull() } ?: 0
    }

    fun isRemoteNewer(
        localPercentage: Double,
        localTimestampSeconds: Long,
        remotePercentage: Double,
        remoteTimestampSeconds: Double?,
        percentageEpsilon: Double,
    ): Boolean {
        if (remoteTimestampSeconds != null) {
            return remoteTimestampSeconds > localTimestampSeconds
        }
        return remotePercentage > localPercentage + percentageEpsilon
    }

    private fun floorToFourDecimals(value: Double): Double = kotlin.math.floor(value * 10000) / 10000

    private fun skipFully(input: InputStream, byteCount: Long): Boolean {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            if (input.read() == -1) return false
            remaining--
        }
        return true
    }

    private fun readUpTo(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val count = input.read(buffer, total, buffer.size - total)
            if (count == -1) break
            total += count
        }
        return total
    }

    private const val SAMPLE_SIZE = 1024L
    private val EPUB_FRAGMENT = Regex(""".*DocFragment\[(\d+)\](?:/.*)?""")
    private val XPATH_OFFSET = Regex("""^(.*?)(?:\.(\d+))$""")
    private val FIRST_INT = Regex("""\d+""")
}
