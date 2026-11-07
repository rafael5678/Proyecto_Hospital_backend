package com.hospy.service;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Open portals for one user. Knows nothing about Redis. */
@Component
public class LiveSessionHub {

    private static final TextMessage PING = new TextMessage("{\"tipo\":\"aviso\"}");
    private final ConcurrentHashMap<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public void add(String email, WebSocketSession session) {
        sessions.computeIfAbsent(email, key -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void remove(WebSocketSession session) {
        sessions.values().forEach(open -> open.remove(session));
    }

    public void sendPing(String email) {
        Set<WebSocketSession> open = sessions.get(email);
        if (open == null) return;
        for (WebSocketSession session : Set.copyOf(open)) {
            if (!session.isOpen()) {
                open.remove(session);
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(PING);
                }
            } catch (Exception ignored) {
                open.remove(session);
            }
        }
    }
}
