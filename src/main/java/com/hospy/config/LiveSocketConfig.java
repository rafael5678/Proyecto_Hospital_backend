package com.hospy.config;

import com.hospy.service.LiveEvents;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class LiveSocketConfig implements WebSocketConfigurer {

    private final LiveEvents liveEvents;
    private final JwtService jwtService;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new Handler(), "/api/live").setAllowedOriginPatterns("*");
    }

    private final class Handler extends TextWebSocketHandler {
        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            String token = tokenOf(session);
            if (token == null || !jwtService.esValido(token)) {
                session.close(CloseStatus.NOT_ACCEPTABLE);
                return;
            }
            liveEvents.register(jwtService.extractEmail(token), session);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            liveEvents.unregister(session);
        }

        private String tokenOf(WebSocketSession session) {
            if (session.getUri() == null) return null;
            return UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams().getFirst("token");
        }
    }
}
