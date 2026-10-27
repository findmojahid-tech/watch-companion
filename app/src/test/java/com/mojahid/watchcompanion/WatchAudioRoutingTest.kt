package com.mojahid.watchcompanion

import org.junit.Assert.*
import org.junit.Test

class WatchAudioRoutingTest {
    private fun d(id: Int, name: String) = WatchAudioRouting.Candidate(id, name)

    @Test fun routesKnownWatchEvenWithOtherBluetoothHeadphonesPresent() {
        assertEquals(2, WatchAudioRouting.choose(
            listOf(d(1, "Pixel Buds"), d(2, "Watch call")), null
        ))
    }

    @Test fun refusesToAutoSelectUnrelatedBluetoothAccessory() {
        assertNull(WatchAudioRouting.choose(
            listOf(d(4, "AirPods Pro")), null
        ))
    }

    @Test fun exactSavedWatchWinsWhenSeveralWatchesExist() {
        assertEquals(3, WatchAudioRouting.choose(
            listOf(d(2, "Watch call"), d(3, "W17 Pro call")), "W17 Pro call"
        ))
    }

    @Test fun doesNotGuessWhenMoreThanOneWatchAudioRouteExists() {
        assertNull(WatchAudioRouting.choose(
            listOf(d(2, "Watch call"), d(3, "W17 Pro call")), null
        ))
    }

    @Test fun acceptsWatchNamesCaseInsensitively() {
        assertEquals(7, WatchAudioRouting.choose(
            listOf(d(7, "watch 7")), null
        ))
    }
    @Test fun savedUserChosenBtCallDeviceCanBeReusedWithoutGuessing() {
        assertEquals(12, WatchAudioRouting.choose(
            listOf(d(10, "Pixel Buds"), d(12, "BT Call")), "BT Call"
        ))
    }

    @Test fun duplicateSavedNameIsNotEnoughToSelectAnUnrelatedHeadset() {
        assertNull(WatchAudioRouting.choose(
            listOf(d(11, "BT Call"), d(12, "BT Call")), "BT Call"
        ))
    }

}
