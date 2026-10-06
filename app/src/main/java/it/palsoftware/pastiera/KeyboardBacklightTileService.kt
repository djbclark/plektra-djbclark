package it.palsoftware.pastiera

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import it.palsoftware.pastiera.inputmethod.DeviceSpecific
import it.palsoftware.pastiera.inputmethod.KeyboardBacklightManager

/**
 * Quick Settings tile that toggles the Unihertz keyboard backlight
 * (`Settings.Global.agui_keyboard_background_light`).
 *
 * Writing the global needs WRITE_SECURE_SETTINGS. The private build gets it from the embedded ADB
 * broker, which grants it once at pairing ([it.palsoftware.pastiera.inputmethod.PrivilegedSetup]).
 * The tile itself never touches the broker: after the grant every write is an ordinary in-process
 * settings write, which is what a tile tap needs.
 *
 * Only offered on the Titan 2 Elite, where the vendor row exists.
 *
 * djb/backlight: two other groups hook in here. The ring group's "keyboard dark while the ring is
 * lit" may have the switch turned off when the user taps, and should hand the tile the value the
 * user actually had (see [KeyboardBacklightManager.writeKeyboardLightSwitch]); the toolbox group's
 * "reset to stock" may want the original value captured before the first write. Neither exists in
 * this tree yet, so the tile toggles the live value.
 */
class KeyboardBacklightTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (!isSupportedDevice()) {
            updateTile()
            return
        }
        if (!KeyboardBacklightManager.canWriteKeyboardLightSwitch(this)) {
            Toast.makeText(
                this,
                getString(R.string.backlight_tile_permission_missing),
                Toast.LENGTH_LONG
            ).show()
            updateTile()
            return
        }
        val newValue = if (readBacklightEnabled()) {
            KeyboardBacklightManager.KEYBOARD_LIGHT_OFF
        } else {
            KeyboardBacklightManager.KEYBOARD_LIGHT_ON
        }
        if (!KeyboardBacklightManager.writeKeyboardLightSwitch(this, newValue)) {
            Log.w(TAG, "Failed to write keyboard backlight setting")
        }
        updateTile()
    }

    private fun isSupportedDevice(): Boolean = DeviceSpecific.isTitan2EliteDevice()

    private fun readBacklightEnabled(): Boolean =
        KeyboardBacklightManager.readKeyboardLightSwitch(this) == KeyboardBacklightManager.KEYBOARD_LIGHT_ON

    private fun updateTile() {
        val tile = qsTile ?: return
        val supported = isSupportedDevice()
        val canWrite = KeyboardBacklightManager.canWriteKeyboardLightSwitch(this)
        tile.state = when {
            !supported || !canWrite -> Tile.STATE_UNAVAILABLE
            readBacklightEnabled() -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.backlight_tile_label)
        tile.subtitle = if (supported && !canWrite) {
            getString(R.string.backlight_tile_no_permission_subtitle)
        } else {
            null
        }
        tile.updateTile()
    }

    private companion object {
        const val TAG = "KbBacklightTile"
    }
}
