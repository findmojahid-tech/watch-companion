package com.mojahid.watchcompanion

/**
 * Choose a Watch call communication endpoint without silently routing
 * audio to an unrelated Bluetooth headset/speaker.
 *
 * Device IDs are from Android AudioDeviceInfo and are transient. Never
 * persist IDs; the friendly name is used only as a safe preference.
 */
object WatchAudioRouting {
    data class Candidate(val id: Int, val label: String)

    fun choose(candidates: List<Candidate>, preferredName: String?): Int? {
        val known = candidates.filter {
            val name = it.label.lowercase(java.util.Locale.ROOT)
            name.contains("watch") || name.contains("w17") ||
                name.contains("momo") || name.contains("wrist")
        }
        if (known.isEmpty()) return null

        val wanted = preferredName?.trim().orEmpty()
        if (wanted.isNotBlank()) {
            val exact = known.filter { it.label.equals(wanted, ignoreCase = true) }
            if (exact.size == 1) return exact.single().id
        }
        // Never choose arbitrarily among two wearable call devices.
        return known.singleOrNull()?.id
    }
}
