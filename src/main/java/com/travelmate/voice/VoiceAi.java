package com.travelmate.voice;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A shared realtime voice model for one team room.
 * Input is mono 16 kHz PCM16; output audio deltas are mono 24 kHz PCM16 (provider event format).
 */
public interface VoiceAi {

    /** Whether a model session can be opened at all (enabled and configured). */
    boolean available();

    /** Human-readable reason when {@link #available()} is false. */
    String unavailableReason();

    /** Opens a session asynchronously; events and the close reason arrive on the listener. */
    Session open(String instructions, Listener listener);

    interface Session {
        void appendAudio(String base64Pcm16k);

        void cancelResponse();

        void updateInstructions(String instructions);

        void close();
    }

    interface Listener {
        void onEvent(JsonNode event);

        void onClosed(String reason);
    }
}
