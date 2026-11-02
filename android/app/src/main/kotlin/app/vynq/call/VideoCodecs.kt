package app.vynq.call

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display

/**
 * Which video format each phone sends: AV1 first, then H.265, then H.264.
 *
 * Each phone tells the other what it can decode (in the call invite and
 * answer) and sends the best format it can encode in hardware that the other
 * can play. Software encoding is never chosen: at 1080p it heats the phone
 * and looks worse than a hardware H.264 stream. H.264 is the universal
 * fallback; every Android phone encodes and decodes it.
 */
object VideoCodecs {
    // LiveKit's codec names (io.livekit.android.room.track.VideoCodec).
    const val AV1 = "av1"
    const val H265 = "h265"
    const val H264 = "h264"

    /** What this phone can decode. AV1 always (WebRTC's dav1d decoder), H.265 only with a hardware decoder. */
    fun receivable(): List<String> = receivable(hardwareH265Decoder = hardware("video/hevc", encoder = false))

    fun receivable(hardwareH265Decoder: Boolean): List<String> =
        buildList { add(AV1); if (hardwareH265Decoder) add(H265); add(H264) }

    /** The format to send to a phone that decodes [theirs] (null: an older vynq, so H.264). */
    fun choose(theirs: List<String>?): String =
        choose(theirs, hardwareAv1 = hardware("video/av01", encoder = true), hardwareH265 = hardware("video/hevc", encoder = true))

    fun choose(theirs: List<String>?, hardwareAv1: Boolean, hardwareH265: Boolean): String {
        val t = theirs.orEmpty()
        return when {
            hardwareAv1 && AV1 in t -> AV1
            hardwareH265 && H265 in t -> H265
            else -> H264
        }
    }

    private fun hardware(mime: String, encoder: Boolean): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder == encoder && isHardware(info) && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }.getOrDefault(false)

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated && !info.isAlias
        else info.name.let { !it.startsWith("OMX.google.") && !it.startsWith("c2.android.") }
}

/**
 * Whether a call sends 1440p instead of 1080p: only on Settings → Maximum, only
 * when the other phone's screen shows more than 1080 pixels across (a 720p or
 * 1080p screen can't show the difference), and only when this phone's front
 * camera delivers 2560×1440 and its hardware encoder does 1440p at 30 fps.
 */
object VideoSize {
    private const val W = 2560
    private const val H = 1440

    /** Short side of this phone's screen in its current display mode, in pixels. */
    fun screenShortSide(context: Context): Int = runCatching {
        val mode = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).mode
        minOf(mode.physicalWidth, mode.physicalHeight)
    }.getOrDefault(0)

    fun send1440(maximum: Boolean, peerScreen: Int?, deviceCan: () -> Boolean): Boolean =
        maximum && (peerScreen ?: 0) > 1080 && deviceCan()

    fun deviceCan1440(context: Context, codec: String): Boolean = frontCamera1440(context) && encoder1440(mime(codec))

    private fun mime(codec: String) = when (codec) {
        VideoCodecs.AV1 -> "video/av01"
        VideoCodecs.H265 -> "video/hevc"
        else -> "video/avc"
    }

    private fun frontCamera1440(context: Context): Boolean = runCatching {
        val cameras = context.getSystemService(CameraManager::class.java)
        val front = cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: return@runCatching false
        val sizes = cameras.getCameraCharacteristics(front).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        sizes.any { it.width == W && it.height == H }
    }.getOrDefault(false)

    private fun encoder1440(mime: String): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && (Build.VERSION.SDK_INT < 29 || info.isHardwareAccelerated) &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                info.getCapabilitiesForType(mime).videoCapabilities.let {
                    it.areSizeAndRateSupported(W, H, 30.0) || it.areSizeAndRateSupported(H, W, 30.0)
                }
        }
    }.getOrDefault(false)
}
