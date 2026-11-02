package app.vynq.call

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

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
