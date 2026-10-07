package com.travelmate.voice;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PcmMixerTest {

    static short[] constant(int n, int value) {
        short[] s = new short[n];
        Arrays.fill(s, (short) value);
        return s;
    }

    @Test
    void sumsSpeakersAndClips() {
        var mixer = new PcmMixer();
        mixer.add("a", constant(PcmMixer.FRAME, 1000));
        mixer.add("b", constant(PcmMixer.FRAME, 2000));
        assertEquals(3000, mixer.mix()[0]);
        mixer.add("a", constant(PcmMixer.FRAME, 30000));
        mixer.add("b", constant(PcmMixer.FRAME, 30000));
        assertEquals(Short.MAX_VALUE, mixer.mix()[10], "overload is clipped, not wrapped");
    }

    @Test
    void nothingQueuedMeansNoFrameAndShortInputIsZeroPadded() {
        var mixer = new PcmMixer();
        assertNull(mixer.mix());
        mixer.add("a", constant(800, 500));
        short[] f = mixer.mix();
        assertEquals(500, f[799]);
        assertEquals(0, f[800]);
    }

    @Test
    void laggingMemberQueueIsCappedKeepingNewestAudio() {
        var mixer = new PcmMixer();
        for (int i = 0; i < 10; i++) mixer.add("a", constant(PcmMixer.FRAME, i));
        // 300 ms cap: only the last three frames (values 7, 8, 9) survive.
        assertEquals(7, mixer.mix()[0]);
        assertEquals(8, mixer.mix()[0]);
        assertEquals(9, mixer.mix()[0]);
        assertNull(mixer.mix());
    }

    @Test
    void pcmRoundTripAndLevel() {
        short[] pcm = {0, 1, -1, 32767, -32768};
        assertArrayEquals(pcm, PcmMixer.decode(PcmMixer.encode(pcm)));
        assertEquals(0, PcmMixer.rms(new short[100]));
        assertTrue(PcmMixer.rms(constant(100, 16384)) > 0.49);
    }
}
