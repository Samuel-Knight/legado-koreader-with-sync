package io.legado.app.help.koReader

import org.junit.Assert.assertEquals
import org.junit.Test

class KoReaderSyncQueueTest {

    @Test
    fun deduplicatesSameDocumentOnSameUtcDay() {
        val first = item(progress = "10", account = "account")
        val second = item(progress = "20", account = "account")

        val firstRaw = KoReaderSyncQueue.enqueue(null, first, nowSeconds = 100_000)
        val secondRaw = KoReaderSyncQueue.enqueue(firstRaw, second, nowSeconds = 100_100)
        val queue = KoReaderSyncQueue.decode(secondRaw)

        assertEquals(1, queue.size)
        assertEquals("20", queue.single().progress)
    }

    @Test
    fun keepsDifferentDocumentsAndAccounts() {
        val first = item(document = "one", account = "account")
        val second = item(document = "two", account = "account")
        val third = item(document = "one", account = "other")

        var raw = KoReaderSyncQueue.enqueue(null, first, nowSeconds = 100_000)
        raw = KoReaderSyncQueue.enqueue(raw, second, nowSeconds = 100_100)
        raw = KoReaderSyncQueue.enqueue(raw, third, nowSeconds = 100_200)

        assertEquals(3, KoReaderSyncQueue.decode(raw).size)
    }

    @Test
    fun expiresEntriesOlderThanFourWeeks() {
        val old = item(document = "old")
        val current = item(document = "current")
        val now = 10_000 + KoReaderSyncQueue.MAX_AGE_SECONDS + 1
        var raw = KoReaderSyncQueue.enqueue(null, old, nowSeconds = 10_000)
        raw = KoReaderSyncQueue.enqueue(raw, current, nowSeconds = now)

        assertEquals("current", KoReaderSyncQueue.decode(raw).single().document)
    }

    @Test
    fun protectsAgainstRunawayQueueGrowth() {
        var raw: String? = null
        repeat(KoReaderSyncQueue.MAX_ENTRIES + 5) { index ->
            raw = KoReaderSyncQueue.enqueue(
                raw,
                item(document = "document-$index"),
                nowSeconds = 100_000L + index,
            )
        }

        val queue = KoReaderSyncQueue.decode(raw)
        assertEquals(KoReaderSyncQueue.MAX_ENTRIES, queue.size)
        assertEquals("document-5", queue.first().document)
    }

    private fun item(
        document: String = "document",
        progress: String = "10",
        account: String = "account",
    ) = KoReaderQueuedProgress(
        account = account,
        document = document,
        progress = progress,
        percentage = 0.5,
        device = "device",
        deviceId = "device-id",
    )
}
