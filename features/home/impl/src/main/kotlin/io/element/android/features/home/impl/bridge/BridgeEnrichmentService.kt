/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.bridge

import dev.zacsweers.metro.Inject
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Lazily enriches bridge type for rooms where hero-based detection returned null.
 * Fetches room members in the background and checks user IDs for bridge bot patterns.
 * Results are stored in [BridgeTypeCache]; callers observe [BridgeTypeCache.cacheFlow].
 *
 * Note: The Rust SDK does not expose a generic getStateEvent() API, so we cannot
 * directly fetch the m.bridge state event. Instead, we inspect room member user IDs
 * for bridge bot naming patterns (same heuristic as [BridgeDetector], but with the
 * full member list instead of just heroes).
 */
@Inject
class BridgeEnrichmentService(
    private val client: MatrixClient,
    private val cache: BridgeTypeCache,
    private val dispatchers: CoroutineDispatchers,
) {
    /**
     * For each room with unknown bridge type, fetch members in the background
     * and run bridge bot detection. Results are stored in [BridgeTypeCache].
     */
    fun enrich(roomIds: List<RoomId>, scope: CoroutineScope) {
        val unchecked = roomIds.filter { !cache.contains(it) }
        if (unchecked.isEmpty()) return

        scope.launch(dispatchers.io) {
            unchecked.forEach { roomId ->
                try {
                    enrichRoom(roomId)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to enrich bridge type for room $roomId")
                    cache.markChecked(roomId)
                }
            }
        }
    }

    private suspend fun enrichRoom(roomId: RoomId) {
        // Use the lightweight path that bypasses the room factory mutex,
        // so background enrichment never blocks room navigation.
        val userIds = client.getRoomMemberUserIds(roomId, limit = 50)
        if (userIds.isEmpty()) {
            cache.markChecked(roomId)
            return
        }
        val detected = BridgeDetector.detect(userIds = userIds)
        if (detected != null) {
            cache.put(roomId, detected)
        } else {
            cache.markChecked(roomId)
        }
    }
}
