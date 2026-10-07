package com.travelmate.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Qwen-Omni realtime over WebSocket, same provider and endpoint allow-list as the single-user gateway.
 * {@code app.team-voice.ai-url} may point at a local stand-in (ws://127.0.0.1 / ws://localhost only) for testing.
 */
@Slf4j
@Component
public class QwenRealtimeVoiceAi implements VoiceAi {

    private static final String MODEL = "qwen3-omni-flash-realtime-2025-12-01";

    private final ObjectMapper mapper;
    private final String key, base, override;
    private final boolean enabled;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public QwenRealtimeVoiceAi(ObjectMapper mapper,
                               @Value("${app.ai.api-key:}") String key,
                               @Value("${app.ai.base-url:}") String base,
                               @Value("${app.realtime.enabled:false}") boolean enabled,
                               @Value("${app.team-voice.ai-url:}") String override) {
        this.mapper = mapper;
        this.key = key;
        this.base = base;
        this.enabled = enabled;
        this.override = override;
    }

    @Override
    public boolean available() {
        return unavailableReason() == null;
    }

    @Override
    public String unavailableReason() {
        if (!enabled) return "实时语音模型未启用(app.realtime.enabled=false)";
        if (key == null || key.isBlank()) return "实时语音模型未配置密钥";
        try {
            target();
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
        return null;
    }

    URI target() {
        if (override != null && !override.isBlank()) {
            URI uri = URI.create(override);
            boolean loopback = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost());
            if (!"ws".equals(uri.getScheme()) || !loopback)
                throw new IllegalStateException("ai-url 覆盖仅允许本机 ws:// 地址(用于本地测试)");
            return uri;
        }
        URI original = URI.create(base == null ? "" : base);
        if (!"https".equals(original.getScheme()) || original.getHost() == null
                || !original.getHost().endsWith(".cn-beijing.maas.aliyuncs.com"))
            throw new IllegalStateException("实时语音仅允许已确认的北京业务空间域名");
        return URI.create("wss://" + original.getHost() + "/api-ws/v1/realtime?model=" + MODEL);
    }

    @Override
    public Session open(String instructions, Listener listener) {
        var session = new QwenSession(listener);
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + key)
                .buildAsync(target(), new WebSocket.Listener() {
                    final StringBuilder parts = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket ws) {
                        if (session.closed.get()) { ws.abort(); return; }
                        session.ws = ws;
                        session.send(Map.of("type", "session.update", "session", Map.of(
                                "modalities", List.of("text", "audio"), "voice", "Cherry",
                                "input_audio_format", "pcm", "output_audio_format", "pcm",
                                "instructions", instructions,
                                "turn_detection", Map.of("type", "server_vad", "threshold", 0.5, "silence_duration_ms", 700),
                                "max_tokens", 300)));
                        ws.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
                        try {
                            parts.append(text);
                            if (parts.length() > 1_048_576) { session.fail("模型事件过大"); return null; }
                            if (last) {
                                JsonNode event = mapper.readTree(parts.toString());
                                parts.setLength(0);
                                if (!session.closed.get()) listener.onEvent(event);
                            }
                        } catch (Exception e) {
                            session.fail("模型返回格式错误");
                        } finally {
                            ws.request(1);
                        }
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                        session.fail("模型会话已结束");
                        return null;
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        session.fail("实时模型连接失败，请检查模型权限和网络");
                    }
                }).whenComplete((ws, error) -> {
                    if (error != null) {
                        Throwable root = error;
                        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                        log.warn("Team voice model connection failure: {}", root.getClass().getSimpleName());
                        session.fail("无法连接实时语音模型");
                    } else if (session.closed.get()) {
                        ws.abort();
                    }
                });
        return session;
    }

    private final class QwenSession implements Session {
        final Listener listener;
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicInteger pending = new AtomicInteger();
        volatile WebSocket ws;
        CompletableFuture<Void> sending = CompletableFuture.completedFuture(null);

        QwenSession(Listener listener) { this.listener = listener; }

        synchronized void send(Object event) {
            if (ws == null || closed.get()) return;
            // Bounded upstream queue: a stalled model connection must not grow memory without limit.
            if (pending.incrementAndGet() > 40) { pending.decrementAndGet(); fail("上行网络拥塞"); return; }
            try {
                String json = mapper.writeValueAsString(event);
                sending = sending.thenCompose(v -> closed.get() ? CompletableFuture.completedFuture(null)
                        : ws.sendText(json, true).thenApply(w -> (Void) null));
                sending.whenComplete((v, e) -> { pending.decrementAndGet(); if (e != null) fail("模型连接中断"); });
            } catch (Exception e) {
                pending.decrementAndGet();
                fail("事件编码失败");
            }
        }

        void fail(String reason) {
            if (!closed.compareAndSet(false, true)) return;
            if (ws != null) ws.abort();
            listener.onClosed(reason);
        }

        @Override public void appendAudio(String base64Pcm16k) { send(Map.of("type", "input_audio_buffer.append", "audio", base64Pcm16k)); }

        @Override public void cancelResponse() { send(Map.of("type", "response.cancel")); }

        @Override public void updateInstructions(String instructions) {
            send(Map.of("type", "session.update", "session", Map.of("instructions", instructions)));
        }

        @Override public void close() {
            if (closed.compareAndSet(false, true) && ws != null) ws.abort();
        }
    }
}
