package com.travelmate.voice;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Mixes members' mono 16 kHz PCM16 into one stream for the shared AI. Not thread-safe; guarded by the room lock. */
final class PcmMixer {

    static final int FRAME = 1600;          // 100 ms at 16 kHz
    static final int MAX_QUEUE = 4800;      // 300 ms per member: bounds latency when a client's clock or network lags

    private static final class Queue {
        short[] data = new short[MAX_QUEUE];
        int length;
    }

    private final Map<String, Queue> queues = new LinkedHashMap<>();

    void add(String member, short[] pcm) {
        Queue q = queues.computeIfAbsent(member, k -> new Queue());
        if (pcm.length >= MAX_QUEUE) {
            System.arraycopy(pcm, pcm.length - MAX_QUEUE, q.data, 0, MAX_QUEUE);
            q.length = MAX_QUEUE;
            return;
        }
        int overflow = q.length + pcm.length - MAX_QUEUE;
        if (overflow > 0) { // drop the oldest samples, never the newest speech
            System.arraycopy(q.data, overflow, q.data, 0, q.length - overflow);
            q.length -= overflow;
        }
        System.arraycopy(pcm, 0, q.data, q.length, pcm.length);
        q.length += pcm.length;
    }

    void remove(String member) {
        queues.remove(member);
    }

    /** One 100 ms frame summed across members with clipping, or null when nobody had audio queued. */
    short[] mix() {
        int[] sum = new int[FRAME];
        boolean any = false;
        for (Iterator<Queue> it = queues.values().iterator(); it.hasNext(); ) {
            Queue q = it.next();
            int take = Math.min(FRAME, q.length);
            if (take == 0) continue;
            any = true;
            for (int i = 0; i < take; i++) sum[i] += q.data[i];
            System.arraycopy(q.data, take, q.data, 0, q.length - take);
            q.length -= take;
        }
        if (!any) return null;
        short[] out = new short[FRAME];
        for (int i = 0; i < FRAME; i++) out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sum[i]));
        return out;
    }

    static short[] decode(byte[] littleEndian) {
        short[] out = new short[littleEndian.length / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (short) ((littleEndian[2 * i] & 0xff) | (littleEndian[2 * i + 1] << 8));
        return out;
    }

    static byte[] encode(short[] pcm) {
        byte[] out = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) {
            out[2 * i] = (byte) pcm[i];
            out[2 * i + 1] = (byte) (pcm[i] >> 8);
        }
        return out;
    }

    /** Root-mean-square level normalised to 0..1. */
    static double rms(short[] pcm) {
        if (pcm.length == 0) return 0;
        double sum = 0;
        for (short s : pcm) sum += (double) s * s;
        return Math.sqrt(sum / pcm.length) / 32768.0;
    }
}
