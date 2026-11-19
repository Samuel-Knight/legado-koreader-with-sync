package io.legado.app.help.koReader

import io.legado.app.help.config.AppConfig
import kotlin.math.abs

enum class KoReaderSyncStrategy(val value: String) {
    SILENT(AppConfig.KO_READER_SYNC_SILENT),
    PROMPT(AppConfig.KO_READER_SYNC_PROMPT),
    DISABLE(AppConfig.KO_READER_SYNC_DISABLE);

    companion object {
        fun from(value: String?): KoReaderSyncStrategy =
            entries.firstOrNull { it.value == value } ?: DISABLE
    }
}

data class KoReaderAutoSyncDecision(
    val strategy: KoReaderSyncStrategy,
    val remoteIsNewer: Boolean,
)

object KoReaderSyncPolicy {
    fun decide(
        localPercentage: Double,
        localTimestampSeconds: Long,
        remotePercentage: Double,
        remoteTimestampSeconds: Double?,
        forwardStrategy: String,
        backwardStrategy: String,
        percentageEpsilon: Double = 0.0001,
    ): KoReaderAutoSyncDecision? {
        if (abs(remotePercentage - localPercentage) <= percentageEpsilon) return null
        val remoteIsNewer = remoteTimestampSeconds
            ?.let { it > localTimestampSeconds }
            ?: (remotePercentage > localPercentage)
        val strategy = KoReaderSyncStrategy.from(
            if (remoteIsNewer) forwardStrategy else backwardStrategy
        )
        return KoReaderAutoSyncDecision(strategy, remoteIsNewer)
            .takeUnless { strategy == KoReaderSyncStrategy.DISABLE }
    }
}
