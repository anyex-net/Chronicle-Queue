/*
 * Copyright 2016-2022 chronicle.software
 *
 *       https://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.queue.impl.single;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.*;
import net.openhft.chronicle.core.util.ThrowingFunction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Thread-safe, self-cleaning cache for ReferenceCounted (and Closeable) objects
 */
public class ReferenceCountedCache<K, T extends ReferenceCounted & Closeable, V, E extends Throwable>
        extends AbstractCloseable {

    private final Map<K, T> cache = new LinkedHashMap<>();
    private final List<T> retained = new ArrayList<>();
    private final Function<T, V> transformer;
    private final ThrowingFunction<K, T, E> creator;
    private final ReferenceChangeListener referenceChangeListener;

    public ReferenceCountedCache(final Function<T, V> transformer,
                                 final ThrowingFunction<K, T, E> creator) {
        this.transformer = transformer;
        this.creator = creator;
        this.referenceChangeListener = new TriggerFlushOnLastReferenceRemoval();

        singleThreadedCheckDisabled(true);
    }

    @NotNull
    V get(@NotNull final K key) throws E {
        throwExceptionIfClosed();

        final V rv;
        synchronized (cache) {
            @Nullable T value = cache.get(key);

            if (value == null) {
                value = creator.apply(key);
                value.reserveTransfer(INIT, this);
                value.addReferenceChangeListener(referenceChangeListener);
                //System.err.println("Reserved " + value.toString() + " by " + this);
                cache.put(key, value);
            }

            // this will add to the ref count and so needs to be done inside of sync block
            rv = transformer.apply(value);
        }

        return rv;
    }

    @Override
    protected void performClose() {
        synchronized (cache) {
            for (T value : cache.values()) {
                try {
                    value.release(this);
                    if (value.refCount() > 0)
                        retained.add(value);
//                    System.err.println("Released (performClose) " + value + " by " + this);
                } catch (Exception e) {
                    Jvm.debug().on(getClass(), e);
                }
            }
            cache.clear();
        }
        if (retained.isEmpty() || !Jvm.isResourceTracing())
            return;

        boolean interrupted = false;
        try {
            for (int i = 1; i <= 2_500; i++) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
                if (retained.stream().noneMatch(v -> v.refCount() > 0)) {
                    (i > 9 ? Jvm.perf() : Jvm.debug())
                            .on(getClass(), "Took " + i + " ms to release " + retained);
                    return;
                }
            }
            retained.stream()
                    .filter(o -> o instanceof ManagedCloseable)
                    .map(o -> (ManagedCloseable) o)
                    .forEach(ManagedCloseable::warnAndCloseIfNotClosed);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void remove(K key) {
        throwExceptionIfClosed();

        synchronized (cache) {
            @Nullable T value = cache.remove(key);

            if (value != null) {
                value.release(this);

                if (value.refCount() > 0)
                    retained.add(value);
            }
        }
    }

    private class TriggerFlushOnLastReferenceRemoval implements ReferenceChangeListener {

        private final Runnable bgCleanup = this::bgCleanup;

        @Override
        public void onReferenceRemoved(ReferenceCounted referenceCounted, ReferenceOwner referenceOwner) {
            if (referenceOwner != ReferenceCountedCache.this && referenceCounted.refCount() == 1) {
                BackgroundResourceReleaser.run(bgCleanup);
            }
        }

        private void bgCleanup() {
            // remove all which have been de-referenced by other than me. Garbagy but rare
            synchronized (cache) {
                cache.entrySet().removeIf(entry -> {
                    T value = entry.getValue();
                    int refCount = value.refCount();
                    if (refCount == 1) {
                        value.release(ReferenceCountedCache.this);
                    }
                    return refCount <= 1;
                });
            }
        }
    }
}
