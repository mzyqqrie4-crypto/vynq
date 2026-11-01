package app.vynq.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Rings for an incoming call by itself (ringtone + vibration, looping), so a
 * call rings even where the phone refuses vynq's full-screen call
 * notification. Follows the phone's ringer mode: silent stays silent,
 * vibrate only vibrates.
 */
class Ringer(private val context: Context) {
    private var ringtone: Ringtone? = null
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

    fun start() {
        stop()
        val mode = context.getSystemService(AudioManager::class.java).ringerMode
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                runCatching { play() }
            }
        }
        if (mode != AudioManager.RINGER_MODE_SILENT) {
            runCatching {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 800, 700), 0),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build(),
                )
            }
        }
    }

    fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
    }
}
