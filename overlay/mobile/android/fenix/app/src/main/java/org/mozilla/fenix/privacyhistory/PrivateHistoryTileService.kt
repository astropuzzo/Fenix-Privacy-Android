/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.preference.PreferenceManager
import org.mozilla.fenix.R

/** Optional Quick Settings shield: status, aggregate counter, and one-tap pause/resume. */
class PrivateHistoryTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val enabled = !prefs.getBoolean(PrivateHistoryRules.KEY_ENABLED, true)
        prefs.edit().putBoolean(PrivateHistoryRules.KEY_ENABLED, enabled).apply()
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val enabled = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(PrivateHistoryRules.KEY_ENABLED, true)
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.private_history_tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (enabled) {
                getString(R.string.private_history_tile_active, PrivateHistoryStats(this).snapshot().total)
            } else {
                getString(R.string.private_history_tile_paused)
            }
        }
        tile.updateTile()
    }
}
