package lv.sergluka.tws.impl.subscription;

import lv.sergluka.tws.IdGenerator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Stores all subscription
 * <p>
 * Holding subscription in one place not only for simple access, but also to restorer them after disconnects,
 * when terminal lost them
 */
public class SubscriptionsRepository implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionsRepository.class);

    public enum EventType {
        EVENT_CONTRACT_PNL,
        EVENT_ACCOUNT_PNL,
        EVENT_POSITION,
        EVENT_ORDER_STATUS,
        EVENT_MARKET_DATA,
        EVENT_PORTFOLIO,
        EVENT_CONNECTION_STATUS
    }

    class Key {
        final EventType type;
        final Integer id;

        Key(EventType type, Integer id) {
            this.type = type;
            this.id = id;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }

            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }

            Key key = (Key) obj;
            return Objects.equals(type, key.type) && Objects.equals(id, key.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, id);
        }
    }

    private final IdGenerator idGenerator;
    private Map<Key, SubscriptionImpl> subscriptions = new ConcurrentHashMap<>();
    private final ExecutorService executors;

    public SubscriptionsRepository(ExecutorService executors, IdGenerator idGenerator) {
        this.executors = executors;
        this.idGenerator = idGenerator;
    }

    public <Param, RegResult> TwsSubscription add(EventType type,
                                                  Consumer<Param> callback,
                                                  Function<Integer, RegResult> registration,
                                                  Consumer<Integer> unregistration) {

        int id = idGenerator.nextRequestId();
        return add(type, id, callback, registration, unregistration);
    }

    @NotNull
    public <Param, RegResult> TwsSubscription addUnique(@Nullable EventType type,
                                                        @NotNull Consumer<Param> callback,
                                                        @Nullable Function<Integer, RegResult> registration,
                                                        @Nullable Consumer<Integer> unregistration) {

        return add(type, null, callback, registration, unregistration);
    }

    @NotNull
    public <Param, RegResult> TwsSubscriptionPromise addPromiseUnique(@Nullable EventType type,
                                                                      @NotNull Consumer<Param> callback,
                                                                      @Nullable Function<Integer, RegResult> registration,
                                                                      @Nullable Consumer<Integer> unregistration) {

        Key key = new Key(type, null);
        SubscriptionPromiseImpl subscription =
                new SubscriptionPromiseImpl(subscriptions, key, callback, registration, unregistration);
        addSubscription(key, subscription);
        return subscription;
    }

    private <Param, RegResult> TwsSubscription add(EventType type,
                                                   Integer id,
                                                   Consumer<Param> callback,
                                                   Function<Integer, RegResult> registration,
                                                   Consumer<Integer> unregistration) {

        Key key = new Key(type, id);
        SubscriptionImpl subscription =
                new SubscriptionImpl<>(subscriptions, key, callback, registration, unregistration);

        addSubscription(key, subscription);
        return subscription;
    }

    public <Param> void eventOnData(EventType type, Param data, Boolean requireSubscription) {
        eventOnData(type, null, data, requireSubscription);
    }

    public <Param> void eventOnData(EventType type, Integer reqId, Param data, Boolean requireSubscription) {
        SubscriptionImpl<Param, Void> subscription = subscriptions.get(new Key(type, reqId));
        if (subscription == null) {
            if (requireSubscription) {
                log.error("Got event '{}' with unknown request id {}", type, reqId);
            } else {
                log.debug("Got event '{}' with unknown request id {}", type, reqId);
            }
            return;
        }

        // TODO: Add queue explicitly and monitor its size
        // TODO: Log posting, execution and finalization of a task
        executors.submit(() -> subscription.call(data));
    }

    public void resubscribe() {
        if (!subscriptions.isEmpty()) {
            log.debug("Restoring {} subscription", subscriptions.size());
        }

        subscriptions.values().forEach( subscription -> {
            log.debug("Subscription {} is restoring", subscription);

            subscription.subscribe(subscription.getId());

            log.info("Subscription {} has been restored", subscription);
        });
        log.info("All subscriptions are restored");
    }

    @Override
    public void close() {
        subscriptions.values().forEach(SubscriptionImpl::close);
        subscriptions.clear();

        executors.shutdownNow().forEach(runnable -> log.warn("Thread still works at shutdown: {}", runnable));

        boolean done = false;
        try {
            done = executors.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.warn("Thread has been interrupted");
        }
        if (!done) {
            log.warn("Still have threads running after shutdown");
        }
    }

    private void addSubscription(Key key, SubscriptionImpl subscription) {
        subscriptions.put(key, subscription);
        subscription.subscribe(key.id);
        log.info("Subscribed to {}", subscription);
    }
}
