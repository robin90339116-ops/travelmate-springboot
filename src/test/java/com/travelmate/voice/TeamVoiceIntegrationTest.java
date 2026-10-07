package com.travelmate.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP + WebSocket against a running app; only the paid realtime model is replaced by a scripted fake. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:teamvoice;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "app.realtime.max-sessions=7"})
class TeamVoiceIntegrationTest {

    /** Scripted stand-in for the realtime model: records what the room sends and lets tests emit model events. */
    static class FakeVoiceAi implements VoiceAi {
        volatile boolean available = true;
        final List<String> instructions = new CopyOnWriteArrayList<>();
        final List<String> audio = new CopyOnWriteArrayList<>();
        volatile int cancels;
        volatile Listener listener;

        @Override public boolean available() { return available; }

        @Override public String unavailableReason() { return available ? null : "实时语音模型未配置密钥"; }

        @Override public Session open(String text, Listener l) {
            instructions.add(text);
            listener = l;
            return new Session() {
                @Override public void appendAudio(String b64) { audio.add(b64); }
                @Override public void cancelResponse() { cancels++; }
                @Override public void updateInstructions(String t) { instructions.add(t); }
                @Override public void close() { }
            };
        }

        void emit(String json) throws Exception { listener.onEvent(new ObjectMapper().readTree(json)); }
    }

    @TestConfiguration
    static class Config {
        @Bean @Primary FakeVoiceAi fakeVoiceAi() { return new FakeVoiceAi(); }
    }

    @LocalServerPort int port;
    @Autowired FakeVoiceAi model;
    @Autowired ObjectMapper json;
    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
        model.available = true;
        model.instructions.clear();
        model.audio.clear();
        model.cancels = 0;
    }

    // ------------------------------------------------------------- helpers

    record User(long id, String token) {}

    User login() throws Exception {
        String phone = "13" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
        JsonNode d = json.readTree(http.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("phone", phone, "code", "246810")).retrieve().body(String.class)).path("data");
        return new User(d.at("/user/id").asLong(), d.path("accessToken").asText());
    }

    JsonNode call(User u, String path, Object body) throws Exception {
        return json.readTree(http.post().uri(path).header("Authorization", "Bearer " + u.token())
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class)).path("data");
    }

    /** Owner creates a team, the others join with the invite code. Returns the team id. */
    long team(User owner, User... others) throws Exception {
        JsonNode t = call(owner, "/api/teams", Map.of());
        for (User o : others) call(o, "/api/teams/join", Map.of("teamCode", t.path("teamCode").asText(), "memberName", "成员" + o.id()));
        return t.path("teamId").asLong();
    }

    class Client extends TextWebSocketHandler {
        final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
        WebSocketSession session;

        @Override protected void handleTextMessage(WebSocketSession s, TextMessage m) throws Exception { inbox.add(json.readTree(m.getPayload())); }

        Client open(User u, long teamId) throws Exception {
            session = new StandardWebSocketClient().execute(this, "ws://127.0.0.1:" + port + "/ws/team-voice").get(5, TimeUnit.SECONDS);
            send(Map.of("type", "join", "token", u.token(), "teamId", teamId));
            return this;
        }

        void send(Object event) throws Exception { session.sendMessage(new TextMessage(json.writeValueAsString(event))); }

        JsonNode await(String type, Predicate<JsonNode> match) throws Exception {
            long until = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < until) {
                JsonNode e = inbox.poll(100, TimeUnit.MILLISECONDS);
                if (e != null && type.equals(e.path("type").asText()) && match.test(e)) return e;
            }
            fail("未在 4 秒内收到 " + type);
            return null;
        }

        /** Asserts no event of the type arrives within the window. */
        void none(String type, long ms) throws Exception {
            long until = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < until) {
                JsonNode e = inbox.poll(50, TimeUnit.MILLISECONDS);
                if (e != null && type.equals(e.path("type").asText())) fail("不应收到 " + type + ": " + e);
            }
        }

        void drain() { inbox.clear(); }
    }

    static String tone(int amplitude) {
        byte[] b = new byte[3200];
        for (int i = 0; i < 1600; i++) {
            short s = (short) (amplitude * Math.sin(2 * Math.PI * 440 * i / 16000.0));
            b[2 * i] = (byte) s;
            b[2 * i + 1] = (byte) (s >> 8);
        }
        return Base64.getEncoder().encodeToString(b);
    }

    static JsonNode memberOf(JsonNode roster, long uid) {
        for (JsonNode m : roster.path("members")) if (m.path("userId").asLong() == uid) return m;
        return null;
    }

    // ------------------------------------------------------------- tests

    @Test
    void nonMemberCannotJoin() throws Exception {
        User owner = login(), stranger = login();
        long teamId = team(owner);
        Client c = new Client().open(stranger, teamId);
        assertTrue(c.await("closed", e -> true).path("message").asText().contains("不在该同游房间"));
    }

    @Test
    void membersHearEachOtherAndToggleMicAndSpeaker() throws Exception {
        User a = login(), b = login();
        long teamId = team(a, b);
        Client ca = new Client().open(a, teamId), cb = new Client().open(b, teamId);
        cb.await("roster", r -> r.path("members").size() == 2);

        ca.send(Map.of("type", "audio", "audio", tone(8000)));
        assertEquals(a.id(), cb.await("peer.audio", e -> true).path("from").asLong(), "B hears A");
        ca.none("peer.audio", 300); // a speaker never hears their own microphone
        assertTrue(memberOf(cb.await("roster", r -> memberOf(r, a.id()).path("speaking").asBoolean()), a.id()).path("speaking").asBoolean());

        cb.send(Map.of("type", "state", "speaker", false));
        cb.await("roster", r -> !memberOf(r, b.id()).path("speaker").asBoolean());
        cb.drain();
        ca.send(Map.of("type", "audio", "audio", tone(8000)));
        cb.none("peer.audio", 400); // speaker off: receives nothing

        cb.send(Map.of("type", "state", "speaker", true));
        ca.send(Map.of("type", "state", "mic", false));
        cb.await("roster", r -> !memberOf(r, a.id()).path("mic").asBoolean() && memberOf(r, b.id()).path("speaker").asBoolean());
        cb.drain();
        int before = model.audio.size();
        ca.send(Map.of("type", "audio", "audio", tone(8000)));
        cb.none("peer.audio", 400); // microphone off: sends nothing to members ...
        assertTrue(model.audio.stream().skip(before).noneMatch(x -> x.equals(tone(8000))), "... nor to the AI");
    }

    @Test
    void aiVoiceReachesEveryoneAndAnyoneCanInterruptIt() throws Exception {
        User a = login(), b = login();
        long teamId = team(a, b);
        Client ca = new Client().open(a, teamId), cb = new Client().open(b, teamId);
        ca.await("roster", r -> r.path("members").size() == 2); // joins processed, this room's AI session is open
        model.emit("{\"type\":\"session.updated\"}");
        ca.await("roster", r -> "listening".equals(r.at("/ai/status").asText()));

        ca.send(Map.of("type", "audio", "audio", tone(8000)));
        Thread.sleep(300);
        assertFalse(model.audio.isEmpty(), "open microphones are mixed into the AI stream");

        model.emit("{\"type\":\"response.created\"}");
        String voice = Base64.getEncoder().encodeToString(new byte[4800]); // 100 ms at 24 kHz, a typical model delta
        model.emit("{\"type\":\"response.audio.delta\",\"delta\":\"" + voice + "\"}");
        ca.await("ai.audio", e -> true);
        cb.await("ai.audio", e -> true);

        // B talks over the AI for 300 ms: the AI is cancelled and both members are told to stop playback.
        for (int i = 0; i < 3; i++) cb.send(Map.of("type", "audio", "audio", tone(9000)));
        assertEquals(b.id(), ca.await("ai.interrupted", e -> true).path("by").asLong());
        cb.await("ai.interrupted", e -> true);
        assertEquals(1, model.cancels);

        // Late audio from the cancelled answer is dropped.
        ca.drain();
        model.emit("{\"type\":\"response.audio.delta\",\"delta\":\"" + voice + "\"}");
        ca.none("ai.audio", 300);

        // The AI's own voice-activity detection also interrupts, and a new answer plays again.
        model.emit("{\"type\":\"response.created\"}");
        model.emit("{\"type\":\"response.audio.delta\",\"delta\":\"" + voice + "\"}");
        ca.await("ai.audio", e -> true);
        model.emit("{\"type\":\"input_audio_buffer.speech_started\"}");
        ca.await("ai.interrupted", e -> e.path("by").asLong() == 0);

        // The explicit button works at any time.
        ca.send(Map.of("type", "interrupt"));
        cb.await("ai.interrupted", e -> e.path("by").asLong() == a.id());
    }

    @Test
    void aiIsBriefedWithTheTeamsSharedPlace() throws Exception {
        User a = login();
        long teamId = team(a);
        call(a, "/api/teams/" + teamId + "/playback", Map.of("currentPointId", "1", "playbackStatus", "stopped"));
        Client ca = new Client().open(a, teamId);
        ca.await("roster", r -> !r.at("/ai/place").asText().isEmpty());
        String brief = model.instructions.get(model.instructions.size() - 1);
        assertTrue(brief.contains("availableFacts") && brief.contains("永定门"), brief);
    }

    @Test
    void roomStillWorksForPeopleWhenAiIsUnavailable() throws Exception {
        model.available = false;
        User a = login(), b = login();
        long teamId = team(a, b);
        Client ca = new Client().open(a, teamId), cb = new Client().open(b, teamId);
        JsonNode r = cb.await("roster", x -> x.path("members").size() == 2);
        assertEquals("unavailable", r.at("/ai/status").asText());
        ca.send(Map.of("type", "audio", "audio", tone(8000)));
        cb.await("peer.audio", e -> true);
    }
}
