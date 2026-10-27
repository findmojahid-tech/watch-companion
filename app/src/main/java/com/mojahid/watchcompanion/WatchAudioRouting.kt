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
        val wanted = preferredName?.trim().orEmpty()
        if (wanted.isNotBlank()) {
            // The user explicitly picked this route in Momo's Watch call list.
            // It may be named "BT Call" rather than "Watch 7".
            val saved = candidates.filter { it.label.equals(wanted, ignoreCase = true) }
            if (saved.size == 1) return saved.single().id
        }

        val known = candidates.filter {
            val name = it.label.lowercase(java.util.Locale.ROOT)
            name.contains("watch") || name.contains("w17") ||
                name.contains("momo") || name.contains("wrist")
        }
        if (known.isEmpty()) return null

        // Never choose arbitrarily among two wearable call devices.
        return known.singleOrNull()?.id
    }
}
