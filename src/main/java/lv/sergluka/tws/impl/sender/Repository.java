package lv.sergluka.tws.impl.sender;

import lv.sergluka.tws.TwsClient;
import lv.sergluka.tws.TwsExceptions;
import lv.sergluka.tws.impl.promise.TwsListPromise;
import lv.sergluka.tws.impl.promise.TwsPromise;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@SuppressWarnings("unchecked")
public class Repository {

    private static final Logger log = LoggerFactory.getLogger(Repository.class);

    public enum Event {
        REQ_CONNECT,
        REQ_CONTRACT_DETAIL,
        REQ_CURRENT_TIME,
        REQ_ORDER_PLACE,
    }

    private final ConcurrentHashMap<EventKey, TwsPromise> promises = new ConcurrentHashMap<>();

    private final TwsClient client;

    public Repository(@NotNull TwsClient client) {
        this.client = client;
    }

    public <T> TwsPromise<T> postSingleRequest(@NotNull Event event, Integer requestId,
                                               @NotNull Runnable runnable, Consumer<T> consumer) {
        if (!client.isConnected()) {
            throw new TwsExceptions.NotConnected();
        }

        final EventKey key = new EventKey(event, requestId);
        final TwsPromise promise = new TwsPromise<>(consumer, () -> promises.remove(key));
        post(key, promise, runnable);
        return promise;
    }

    public <T> TwsPromise<T> postListRequest(@NotNull Event event, int requestId,
                                             @NotNull Runnable runnable, Consumer<List<T>> consumer) {
        if (!client.isConnected()) {
            throw new TwsExceptions.NotConnected();
        }

        final EventKey key = new EventKey(event, requestId);
        final TwsPromise promise = new TwsListPromise<>(consumer, () -> promises.remove(key));
        post(key, promise, runnable);
        return promise;
    }

    public <T> void postUncheckedRequest(@NotNull Event event, @NotNull Runnable runnable) {
        final EventKey key = new EventKey(event, null);
        final TwsPromise promise = new TwsPromise<T>(null, () -> promises.remove(key));
        post(key, promise, runnable);
    }

    public void confirmResponse(@NotNull Event event, @Nullable Integer id, @Nullable Object result) {
        confirm(event, id, result);
    }

    public void removeRequest(@NotNull Event event, @NotNull Integer id) {
        final EventKey key = new EventKey(event, id);
        if (promises.remove(key) == null) {
            log.error("Try to remove unknown promise {}", key);
        }
    }

    public void setError(int requestId, RuntimeException exception) {
        final EventKey key = new EventKey(null, requestId);
        final TwsPromise promise = promises.get(key);
        if (promise == null) {
            log.error("Cannot set error for unknown promise with ID {}", requestId);
            return;
        }

        promise.setException(exception);
    }

    public <E> void addElement(@NotNull Event event, int id, @NotNull E element) {
        final EventKey key = new EventKey(event, id);
        log.debug("=> {}:add", key);

        final TwsListPromise promise = (TwsListPromise) promises.get(key);
        if (promise == null) {
            log.error("Got event {} for unknown or expired request", key);
            return;
        }

        promise.add(element);
    }

    private void post(EventKey key, @NotNull TwsPromise promise, @NotNull Runnable runnable) {
        promises.put(key, promise);
        try {
            log.debug("<= {}", key);
            runnable.run();
        } catch (Exception e) {
            promises.remove(key); // TODO: wrap with lock all procedure?
            throw e;
        }
    }

    private void confirm(@NotNull Event event, Integer id, Object result) {
        final EventKey key = new EventKey(event, id);
        log.debug("=> {}: {}", key, result);

        final TwsPromise promise = promises.remove(key);
        if (promise == null) {
            log.error("Got event {} for unknown or expired request", key);
            return;
        }

        promise.setDone(result);
    }
}
