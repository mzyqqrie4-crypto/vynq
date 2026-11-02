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
