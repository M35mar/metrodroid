/*
 * Ricaricami2004Tap.kt
 *
 * Copyright 2026 M35mar
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package au.id.micolous.metrodroid.transit.ricaricami

import au.id.micolous.metrodroid.multi.FormattedString
import au.id.micolous.metrodroid.multi.Localizer
import au.id.micolous.metrodroid.multi.Parcelize
import au.id.micolous.metrodroid.multi.Parcelable
import au.id.micolous.metrodroid.multi.R
import au.id.micolous.metrodroid.ui.ListItem
import au.id.micolous.metrodroid.ui.ListItemInterface
import au.id.micolous.metrodroid.util.ImmutableByteArray
import au.id.micolous.metrodroid.util.NumberUtils

/**
 * One event of the alternating two-slot validation log (pages 6/24-26 and 7/30-32).
 *
 * Layout derived from diffs of the collected dataset (see the documentation in
 * [Ricaricami2004UltralightTransitData]):
 *
 * - timestamp page: bits 8..23 = daycode + 4 x minutes-of-day,
 *   byte 3 = reader family (0x04 ATM, 0x08 Trenord)
 * - event page: byte 0 = event kind, byte 3 = station code
 * - info page: reader-dependent, not decoded
 */
@Parcelize
data class Ricaricami2004Tap(
        val slot: String,
        val timeMinutes: Int?,
        val stationCode: Int?,
        val reader: Int?,
        val kindCode: Int?,
        val timestampWord: ImmutableByteArray,
        val eventWord: ImmutableByteArray,
        val infoWord: ImmutableByteArray,
        private val stationNames: Map<Int, String>) : Parcelable {

    private val kindString: String
        get() = when (kindCode) {
            0x61 -> R.string.ricaricami2004_tap_in
            0x62 -> R.string.ricaricami2004_tap_out
            0x66 -> R.string.ricaricami2004_tap_reentry
            0xA1 -> R.string.ricaricami2004_tap_in_train
            0xA2 -> R.string.ricaricami2004_tap_out_train
            0xA6 -> R.string.ricaricami2004_tap_scan_train
            else -> R.string.ricaricami2004_tap_unknown
        }.let { Localizer.localizeString(it) }

    /** "08:10", or the raw state when the day code makes the parse unsafe. */
    private val timeString: String
        get() = timeMinutes?.let { "${NumberUtils.zeroPad(it / 60, 2)}:${NumberUtils.zeroPad(it % 60, 2)}" }
                ?: NumberUtils.intToHex(timestampWord.byteArrayToInt(1, 2))

    private val stationString: String
        get() = when {
            stationCode == null -> "-"
            stationNames.containsKey(stationCode) -> stationNames[stationCode]!!
            else -> NumberUtils.byteToHex(stationCode.toByte())
        }

    val displayText: String
        get() = listOf(timeString, stationString).joinToString(" — ")

    fun toListItems(): ListItemInterface = ListItem(
            FormattedString(kindString),
            FormattedString(displayText))

    companion object {
        /**
         * Bits 8..23 of the timestamp page hold an unresolved day code plus
         * 4 x minutes-of-day; the minutes always fit in 13 bits (23:59 = 5756).
         */
        fun unwrapTime(t16: Int): Int? {
            val raw = t16 and 0x1FFF
            return if (raw % 4 == 0 && raw / 4 < 1440) raw / 4 else null
        }

        fun parse(slot: String, timestamp: ImmutableByteArray, event: ImmutableByteArray,
                  info: ImmutableByteArray, stationNames: Map<Int, String>):
                Ricaricami2004Tap = Ricaricami2004Tap(
                slot = slot,
                timeMinutes = unwrapTime(timestamp.byteArrayToInt(1, 2)),
                stationCode = event[3].toInt() and 0xFF,
                reader = timestamp[3].toInt() and 0xFF,
                kindCode = event[0].toInt() and 0xFF,
                timestampWord = timestamp,
                eventWord = event,
                infoWord = info,
                stationNames = stationNames)
    }
}
