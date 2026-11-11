package com.nuvio.app.features.player

import com.nuvio.app.core.network.NetworkCondition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SubtitleFetchRetryTest {
    @Test
    fun manualRetryIgnoresStaleConnectivityProbe() {
        NetworkCondition.entries.forEach { condition ->
            assertTrue(shouldFetchAddonSubtitles(condition, manual = true), condition.name)
        }
    }

    @Test
    fun automaticFetchOnlyWaitsForInternetNotNuvioBackend() {
        assertFalse(shouldFetchAddonSubtitles(NetworkCondition.NoInternet, manual = false))
        NetworkCondition.entries.filter { it != NetworkCondition.NoInternet }.forEach { condition ->
            assertTrue(shouldFetchAddonSubtitles(condition, manual = false), condition.name)
        }
    }

    @Test
    fun retryForwardsCacheBypassToEveryAddon() = runTest {
        val requests = listOf(
            SubtitleAddonRequest("https://one.example/subtitles.json", "one", "One"),
            SubtitleAddonRequest("https://two.example/subtitles.json", "two", "Two"),
        )
        val calls = mutableListOf<Pair<String, Boolean>>()
        val completed = mutableListOf<String>()
        loadAddonSubtitles(requests, forceRefresh = true, fetchResponse = { url, refresh ->
            calls += url to refresh
            """{"subtitles":[]}"""
        }) { request, _ -> completed += request.addonId }
        assertEquals(requests.map { it.url to true }.toSet(), calls.toSet())
        assertEquals(setOf("one", "two"), completed.toSet())
    }

    @Test
    fun oneFailedAddonDoesNotCancelOtherRequests() = runTest {
        val completed = mutableListOf<String>()
        val requests = listOf(
            SubtitleAddonRequest("https://broken.example", "broken", "Broken"),
            SubtitleAddonRequest("https://working.example", "working", "Working"),
        )
        loadAddonSubtitles(requests, fetchResponse = { url, refresh ->
            assertFalse(refresh)
            if (url.contains("broken")) error("Network failure")
            """{"subtitles":[]}"""
        }) { request, _ -> completed += request.addonId }
        assertEquals(setOf("broken", "working"), completed.toSet())
    }

    @Test
    fun cancellationDoesNotPublishAnEmptyResult() = runTest {
        var published = false
        assertFailsWith<CancellationException> {
            loadAddonSubtitles(
                listOf(SubtitleAddonRequest("https://addon.example", "addon", "Addon")),
                fetchResponse = { _, _ -> throw CancellationException("Playback changed") },
            ) { _, _ -> published = true }
        }
        assertFalse(published)
    }
}
