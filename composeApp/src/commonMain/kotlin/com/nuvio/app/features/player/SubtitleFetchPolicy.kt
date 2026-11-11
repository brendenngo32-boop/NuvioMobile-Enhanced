package com.nuvio.app.features.player

import com.nuvio.app.core.network.NetworkCondition

// Addons use independent servers. A failed Nuvio backend probe must not block them,
// and an explicit retry must be allowed even when the last internet probe is stale.
internal fun shouldFetchAddonSubtitles(condition: NetworkCondition, manual: Boolean): Boolean =
    manual || condition != NetworkCondition.NoInternet
