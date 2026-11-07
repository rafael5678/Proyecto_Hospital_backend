package com.hospy.config;

import com.hospy.service.LiveSessionHub;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class LiveSocketHandler extends TextWebSocketHandler {

    private final LiveSessionHub hub;
    private final JwtService jwtService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String token = tokenOf(session);
        if (token == null || !jwtService.isValid(token)) {
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        hub.add(jwtService.extractEmail(token), session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        hub.remove(session);
    }

    private String tokenOf(WebSocketSession session) {
        if (session.getUri() == null) return null;
        return UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams().getFirst("token");
    }
}
