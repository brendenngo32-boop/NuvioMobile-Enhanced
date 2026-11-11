package com.nuvio.app.features.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_no_subtitles_found
import nuvio.composeapp.generated.resources.compose_player_no_compatible_subtitle_addons
import org.jetbrains.compose.resources.getString

object SubtitleRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _addonSubtitles = MutableStateFlow<List<AddonSubtitle>>(emptyList())
    val addonSubtitles: StateFlow<List<AddonSubtitle>> = _addonSubtitles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadingProgress = MutableStateFlow<SubtitleLoadingProgress?>(null)
    internal val loadingProgress: StateFlow<SubtitleLoadingProgress?> = _loadingProgress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var activeFetchJob: Job? = null

    fun fetchAddonSubtitles(
        type: String,
        videoId: String,
        extras: SubtitleRequestExtras? = null,
        forceRefresh: Boolean = false,
    ) {
        activeFetchJob?.cancel()
        _loadingProgress.value = null
        activeFetchJob = scope.launch {
            executeSubtitleFetch(type, videoId, extras, forceRefresh)
        }
    }

    /**
     * Like [fetchAddonSubtitles], but first computes the OpenSubtitles video hash
     * when the stream extras do not already contain one.
     */
    fun fetchAddonSubtitlesWithHashComputation(
        type: String,
        videoId: String,
        extras: SubtitleRequestExtras?,
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        forceRefresh: Boolean = false,
        onExtrasUpdated: (SubtitleRequestExtras) -> Unit,
    ) {
        activeFetchJob?.cancel()
        _loadingProgress.value = null
        activeFetchJob = scope.launch {
            _isLoading.value = true
            _error.value = null
            _addonSubtitles.value = emptyList()

            val enrichedExtras = try {
                val hashResult = computeVideoHash(sourceUrl, sourceHeaders)
                if (hashResult != null) {
                    val (hash, fileSize) = hashResult
                    val updated = (extras ?: SubtitleRequestExtras()).copy(
                        videoHash = hash,
                        videoSize = extras?.videoSize ?: fileSize,
                    )
                    onExtrasUpdated(updated)
                    updated
                } else extras
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                extras
            }

            executeSubtitleLoad(type, videoId, enrichedExtras, forceRefresh)
        }
    }

    fun clear() {
        activeFetchJob?.cancel()
        _loadingProgress.value = null
        _addonSubtitles.value = emptyList()
        _isLoading.value = false
        _error.value = null
    }

    private suspend fun executeSubtitleFetch(
        type: String,
        videoId: String,
        extras: SubtitleRequestExtras?,
        forceRefresh: Boolean,
    ) {
        _isLoading.value = true
        _error.value = null
        _addonSubtitles.value = emptyList()
        executeSubtitleLoad(type, videoId, extras, forceRefresh)
    }

    private suspend fun executeSubtitleLoad(
        type: String,
        videoId: String,
        extras: SubtitleRequestExtras?,
        forceRefresh: Boolean,
    ) {
        val requests = addonSubtitleRequests(type, videoId, extras)
        if (requests.isEmpty()) {
            _error.value = getString(Res.string.compose_player_no_compatible_subtitle_addons)
            _isLoading.value = false
            return
        }

        _loadingProgress.value = SubtitleLoadingProgress(total = requests.size)
        loadAddonSubtitles(requests, forceRefresh = forceRefresh) { request, subtitles ->
            _addonSubtitles.update { it + subtitles }
            _loadingProgress.update { progress ->
                progress?.copy(completed = progress.completed + 1, addonName = request.addonName)
            }
        }

        if (_addonSubtitles.value.isEmpty()) {
            _error.value = getString(Res.string.compose_player_no_subtitles_found)
        }
        _isLoading.value = false
    }
}

internal data class SubtitleLoadingProgress(
    val total: Int,
    val completed: Int = 0,
    val addonName: String? = null,
)
