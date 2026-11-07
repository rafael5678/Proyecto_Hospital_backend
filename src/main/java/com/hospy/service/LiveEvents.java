package com.hospy.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * After a notification is saved, tells that user's open portal.
 * Redis repeats the ping to every server. Without Redis, this server delivers it.
 */
@Service
public class LiveEvents {

    private final LiveSessionHub hub;
    private final RedisLiveBus bus;

    public LiveEvents(LiveSessionHub hub, @Value("${hospy.redis.url:}") String redisUrl) {
        this.hub = hub;
        this.bus = RedisLiveBus.tryOpen(redisUrl, hub::sendPing);
    }

    public void notifyUser(String email) {
        if (email == null || email.isBlank()) return;
        if (bus != null && bus.publish(email)) return;
        hub.sendPing(email);
    }

    @PreDestroy
    public void close() {
        if (bus != null) bus.close();
    }
}
