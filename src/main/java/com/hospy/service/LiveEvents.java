package com.hospy.service;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells an open portal that a notification was saved.
 * Redis only forwards that ping between servers. If Redis is absent, this server delivers it.
 */
@Service
public class LiveEvents {

    private static final String CHANNEL = "hospy.live";
    private static final TextMessage PING = new TextMessage("{\"tipo\":\"aviso\"}");

    private final ConcurrentHashMap<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private RedisClient redisClient;
    private StatefulRedisPubSubConnection<String, String> redisConnection;
    private volatile boolean redisReady;

    public LiveEvents(@Value("${hospy.redis.url:}") String redisUrl) {
        if (redisUrl == null || redisUrl.isBlank()) return;
        try {
            RedisURI uri = RedisURI.create(redisUrl.trim());
            uri.setTimeout(Duration.ofMillis(500));
            redisClient = RedisClient.create(uri);
            redisConnection = redisClient.connectPubSub();
            redisConnection.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String channel, String email) {
                    deliver(email);
                }
            });
            RedisPubSubCommands<String, String> sync = redisConnection.sync();
            sync.subscribe(CHANNEL);
            redisReady = true;
        } catch (Exception ignored) {
            redisReady = false;
            closeRedis();
        }
    }

    public void register(String email, WebSocketSession session) {
        sessions.computeIfAbsent(email, key -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unregister(WebSocketSession session) {
        sessions.values().forEach(set -> set.remove(session));
    }

    public void notifyUser(String email) {
        if (email == null || email.isBlank()) return;
        if (redisReady && redisConnection != null) {
            try {
                redisConnection.sync().publish(CHANNEL, email);
                return;
            } catch (Exception ignored) {
                redisReady = false;
            }
        }
        deliver(email);
    }

    private void deliver(String email) {
        Set<WebSocketSession> open = sessions.get(email);
        if (open == null) return;
        for (WebSocketSession session : open) {
            if (!session.isOpen()) continue;
            try {
                synchronized (session) {
                    session.sendMessage(PING);
                }
            } catch (Exception ignored) {
                open.remove(session);
            }
        }
    }

    @PreDestroy
    public void closeRedis() {
        redisReady = false;
        try {
            if (redisConnection != null) redisConnection.close();
        } catch (Exception ignored) {
            /* already closed */
        }
        try {
            if (redisClient != null) redisClient.shutdown();
        } catch (Exception ignored) {
            /* already closed */
        }
    }
}
