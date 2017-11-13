package lv.sergluka.tws.impl.promise;

import lv.sergluka.tws.TwsExceptions;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public class TwsPromise<T> {

    private static final Logger log = LoggerFactory.getLogger(TwsPromise.class);

    protected T value;
    protected AtomicBoolean done = new AtomicBoolean(false);

    private final Runnable onTimeout;
    private final Consumer<T> consumer;

    private final Condition condition;
    private final Lock lock;

    private RuntimeException exception;

    public TwsPromise(Consumer<T> consumer, Runnable onTimeout) {
        this.consumer = consumer;
        this.onTimeout = onTimeout;

        lock = new ReentrantLock();
        condition = lock.newCondition();
    }

//    public boolean isDone() {
//        return done;
//    }

    public T get() {
        try {
            lock.lock();
            while (!done.get()) {
                condition.await();
            }
        } catch (InterruptedException e) {
            return null;
        }
        finally {
            lock.unlock();
        }

        if (exception != null) {
            throw exception;
        }
        return value;
    }

    public T get(long timeout, TimeUnit unit) {
        try {
            lock.lock();
            while (!done.get()) {
                if (!condition.await(timeout, unit)) {
                    onTimeout.run();
                    throw new TwsExceptions.ResponseTimeout("Request timeout");
                }
            }
        } catch (InterruptedException e) {
            log.warn("Promise has been interrupted");
            return null;
        } finally {
            lock.unlock();
        }

        if (exception != null) {
            throw exception;
        }
        return value;
    }

    public void setDone(T value) {
        lock.lock();
        try {
            if (value != null) {
                this.value = value;
            }
            if (consumer != null) {
                consumer.accept(this.value);
            }

            done.set(true);
            condition.signal();
        } finally {
            lock.unlock();
        }
    }

    public void setException(RuntimeException e) {
        lock.lock();
        try {
            exception = e;
            done.set(true);
            condition.signal();
        } finally {
            lock.unlock();
        }
    }
}
