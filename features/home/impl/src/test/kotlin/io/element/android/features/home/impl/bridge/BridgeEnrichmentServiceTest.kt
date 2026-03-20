/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.bridge

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BridgeEnrichmentServiceTest {
    private val roomId1 = RoomId("!room1:matrix.org")
    private val roomId2 = RoomId("!room2:matrix.org")
    private val roomId3 = RoomId("!room3:matrix.org")

    private fun fakeClient(membersByRoom: Map<RoomId, List<String>> = emptyMap()) =
        FakeMatrixClient(
            getRoomMemberUserIdsLambda = { roomId, _ -> membersByRoom[roomId] ?: emptyList() }
        )

    // --- enrich() filtering ---

    @Test
    fun `enrich skips rooms already in cache`() = runTest {
        val cache = BridgeTypeCache().apply { put(roomId1, BridgeType.DISCORD) }
        val client = fakeClient(mapOf(roomId1 to listOf("@signalbot:matrix.org")))
        val service = BridgeEnrichmentService(client, cache, testCoroutineDispatchers())

        service.enrich(listOf(roomId1), this)
        testScheduler.advanceUntilIdle()

        // Value should remain unchanged — room was already cached, not re-fetched
        assertThat(cache.get(roomId1)).isEqualTo(BridgeType.DISCORD)
    }

    @Test
    fun `enrich does nothing when all rooms are already cached`() = runTest {
        val cache = BridgeTypeCache().apply {
            put(roomId1, BridgeType.SIGNAL)
            put(roomId2, BridgeType.TELEGRAM)
        }
        val service = BridgeEnrichmentService(fakeClient(), cache, testCoroutineDispatchers())

        service.enrich(listOf(roomId1, roomId2), this)
        testScheduler.advanceUntilIdle()

        assertThat(cache.get(roomId1)).isEqualTo(BridgeType.SIGNAL)
        assertThat(cache.get(roomId2)).isEqualTo(BridgeType.TELEGRAM)
    }

    @Test
    fun `enrich does nothing for empty room list`() = runTest {
        val cache = BridgeTypeCache()
        val service = BridgeEnrichmentService(fakeClient(), cache, testCoroutineDispatchers())

        service.enrich(emptyList(), this)
        testScheduler.advanceUntilIdle()

        assertThat(cache.cacheFlow.value).isEmpty()
    }

    // --- enrichRoom() paths ---

    @Test
    fun `enrich marks room checked when member list is empty`() = runTest {
        val cache = BridgeTypeCache()
        val client = fakeClient(mapOf(roomId1 to emptyList()))
        val service = BridgeEnrichmentService(client, cache, testCoroutineDispatchers())

        service.enrich(listOf(roomId1), this)
        testScheduler.advanceUntilIdle()

        assertThat(cache.contains(roomId1)).isTrue()
        assertThat(cache.get(roomId1)).isEqualTo(BridgeType.NONE)
    }

    @Test
    fun `enrich detects bridge and stores in cache`() = runTest {
        val cache = BridgeTypeCache()
        val client = fakeClient(mapOf(roomId1 to listOf("@alice:matrix.org", "@discordbot:matrix.org")))
        val service = BridgeEnrichmentService(client, cache, testCoroutineDispatchers())

        service.enrich(listOf(roomId1), this)
        testScheduler.advanceUntilIdle()

        assertThat(cache.get(roomId1)).isEqualTo(BridgeType.DISCORD)
    }

    @Test
    fun `enrich marks room checked when no bridge is detected`() = runTest {
        val cache = BridgeTypeCache()
        val client = fakeClient(mapOf(roomId1 to listOf("@alice:matrix.org", "@bob:matrix.org")))
        val service = BridgeEnrichmentService(client, cache, testCoroutineDispatchers())

        service.enrich(listOf(roomId1), this)
        testScheduler.advanceUntilIdle()

        assertThat(cache.contains(roomId1)).isTrue()
        assertThat(cache.get(roomId1)).isEqualTo(BridgeType.NONE)
    }

    @Test
    fun `enrich processes multiple uncached rooms`() = runTest {
        val cache = BridgeTypeCache()
        val client = fakeClient(
            mapOf(
                roomId1 to listOf("@signalbot:matrix.org"),
                roomId2 to listOf("@alice:matrix.org"),
                roomId3 to listOf("@whatsappbot:matrix.org"),
            )
        )
        val service = BridgeEnrichmentService(client, cache, testCoroutineDispatchers())

        service.enrich(listOf(roomId1, roomId2, roomId3), this)
        testScheduler.advanceUntilIdle()

        assertThat(cache.get(roomId1)).isEqualTo(BridgeType.SIGNAL)
        assertThat(cache.get(roomId2)).isEqualTo(BridgeType.NONE)
        assertThat(cache.get(roomId3)).isEqualTo(BridgeType.WHATSAPP)
    }

    @Test
    fun `enrich skips cached rooms but processes uncached ones`() = runTest {
        val cache = BridgeTypeCache().apply { put(roomId1, BridgeType.TELEGRAM) }
        val client = fakeClient(mapOf(roomId2 to listOf("@slackbot:matrix.org")))
        val service = BridgeEnrichmentService(client, cache, testCoroutineDispatchers())

        service.enrich(listOf(roomId1, roomId2), this)
        testScheduler.advanceUntilIdle()

        assertThat(cache.get(roomId1)).isEqualTo(BridgeType.TELEGRAM) // unchanged
        assertThat(cache.get(roomId2)).isEqualTo(BridgeType.SLACK)
    }

    @Test
    fun `enrich marks room checked and swallows exception`() = runTest {
        val cache = BridgeTypeCache()
        val client = FakeMatrixClient(
            getRoomMemberUserIdsLambda = { _, _ -> throw RuntimeException("Simulated SDK failure") }
        )
        val service = BridgeEnrichmentService(client, cache, testCoroutineDispatchers())

        service.enrich(listOf(roomId1), this)
        testScheduler.advanceUntilIdle()

        assertThat(cache.contains(roomId1)).isTrue()
        assertThat(cache.get(roomId1)).isEqualTo(BridgeType.NONE)
    }

    // --- Matrix bridge type detection end-to-end ---

    @Test
    fun `enrich detects Matrix bridge type`() = runTest {
        val cache = BridgeTypeCache()
        val client = fakeClient(mapOf(roomId1 to listOf("@matrixbot:example.com")))
        val service = BridgeEnrichmentService(client, cache, testCoroutineDispatchers())

        service.enrich(listOf(roomId1), this)
        testScheduler.advanceUntilIdle()

        assertThat(cache.get(roomId1)).isEqualTo(BridgeType.MATRIX)
    }
}
