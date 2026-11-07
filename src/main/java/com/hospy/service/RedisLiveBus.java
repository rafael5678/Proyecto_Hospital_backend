package com.hospy.service;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * One connection publishes and another listens.
 * A subscribed Redis connection is not allowed to publish.
 */
final class RedisLiveBus implements AutoCloseable {

    static final String CHANNEL = "hospy.live";

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> publisher;
    private final StatefulRedisPubSubConnection<String, String> subscriber;

    private RedisLiveBus(RedisClient client,
                         StatefulRedisConnection<String, String> publisher,
                         StatefulRedisPubSubConnection<String, String> subscriber) {
        this.client = client;
        this.publisher = publisher;
        this.subscriber = subscriber;
    }

    static RedisLiveBus tryOpen(String url, Consumer<String> onEmail) {
        if (url == null || url.isBlank()) return null;
        RedisClient client = null;
        try {
            RedisURI uri = RedisURI.create(url.trim());
            uri.setTimeout(Duration.ofMillis(500));
            client = RedisClient.create(uri);
            StatefulRedisConnection<String, String> publisher = client.connect();
            StatefulRedisPubSubConnection<String, String> subscriber = client.connectPubSub();
            subscriber.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String channel, String email) {
                    onEmail.accept(email);
                }
            });
            subscriber.sync().subscribe(CHANNEL);
            return new RedisLiveBus(client, publisher, subscriber);
        } catch (Exception ignored) {
            if (client != null) client.shutdown();
            return null;
        }
    }

    boolean publish(String email) {
        try {
            publisher.sync().publish(CHANNEL, email);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public void close() {
        try {
            subscriber.close();
        } catch (Exception ignored) {
            /* already closed */
        }
        try {
            publisher.close();
        } catch (Exception ignored) {
            /* already closed */
        }
        try {
            client.shutdown();
        } catch (Exception ignored) {
            /* already closed */
        }
    }
}
