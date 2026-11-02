package app.vynq.call

import app.vynq.call.VideoCodecs.AV1
import app.vynq.call.VideoCodecs.H264
import app.vynq.call.VideoCodecs.H265
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoCodecsTest {
    private val all = listOf(AV1, H265, H264)

    @Test fun av1FirstWhenThisPhoneEncodesItInHardware() =
        assertEquals(AV1, VideoCodecs.choose(all, hardwareAv1 = true, hardwareH265 = true))

    @Test fun h265WithoutHardwareAv1() =
        assertEquals(H265, VideoCodecs.choose(all, hardwareAv1 = false, hardwareH265 = true))

    @Test fun h265OnlyWhenTheOtherPhoneDecodesIt() =
        assertEquals(H264, VideoCodecs.choose(listOf(AV1, H264), hardwareAv1 = false, hardwareH265 = true))

    @Test fun h264WhenNothingBetterInHardware() =
        assertEquals(H264, VideoCodecs.choose(all, hardwareAv1 = false, hardwareH265 = false))

    @Test fun olderVynqGetsH264() =
        assertEquals(H264, VideoCodecs.choose(null, hardwareAv1 = true, hardwareH265 = true))

    @Test fun receivableAlwaysListsAv1AndH264() {
        assertEquals(listOf(AV1, H264), VideoCodecs.receivable(hardwareH265Decoder = false))
        assertEquals(listOf(AV1, H265, H264), VideoCodecs.receivable(hardwareH265Decoder = true))
    }
}

class VideoSizeTest {
    @Test fun qhdOnlyOnMaximumToALargeScreenWhenThisPhoneCan() {
        assertEquals(true, VideoSize.send1440(maximum = true, peerScreen = 1220) { true })
        assertEquals(false, VideoSize.send1440(maximum = false, peerScreen = 1440) { true })
        assertEquals(false, VideoSize.send1440(maximum = true, peerScreen = 720) { true })   // 720p screen
        assertEquals(false, VideoSize.send1440(maximum = true, peerScreen = 1080) { true })  // 1080p screen
        assertEquals(false, VideoSize.send1440(maximum = true, peerScreen = null) { true })  // older vynq
        assertEquals(false, VideoSize.send1440(maximum = true, peerScreen = 1440) { false }) // camera/encoder can't
    }
}

class CallProfileTest {
    @Test fun heatStepDownLevelsGoFromLeastToMostData() {
        val rates = CallProfile.Levels.map { it.profile.encoding.maxBitrate }
        assertEquals(rates.sorted(), rates)
        assertEquals(rates.size, rates.distinct().size)
    }

    @Test fun everyQualitySettingHasALevel() {
        app.vynq.data.prefs.CallQuality.entries.forEach { q ->
            assertEquals(true, CallProfile.Levels.any { it.quality == q })
        }
    }
}
