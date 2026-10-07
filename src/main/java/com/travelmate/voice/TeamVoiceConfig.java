package com.travelmate.voice;

import com.travelmate.common.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.config.annotation.ServletWebSocketHandlerRegistry;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Map;

/** Registers /ws/team-voice ahead of the STOMP endpoint (same origin allow-list as /ws/realtime). */
@Configuration
class TeamVoiceConfig implements WebSocketConfigurer {
    private final TeamVoiceGateway gateway;
    private final String[] origins;

    TeamVoiceConfig(TeamVoiceGateway gateway,
                    @Value("${app.realtime.origins:http://127.0.0.1:5173,http://localhost:5173}") String[] origins) {
        this.gateway = gateway;
        this.origins = origins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        ((ServletWebSocketHandlerRegistry) registry).setOrder(-1);
        registry.addHandler(gateway, "/ws/team-voice").setAllowedOrigins(origins);
    }
}

@RestController
@RequestMapping("/api/team-voice")
class TeamVoiceStatusController {
    private final TeamVoiceGateway gateway;

    TeamVoiceStatusController(TeamVoiceGateway gateway) { this.gateway = gateway; }

    @GetMapping("/status")
    public Result<Map<String, Object>> status() { return Result.ok(gateway.status()); }
}
