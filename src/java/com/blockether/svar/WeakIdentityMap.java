package com.blockether.svar;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * A map from an object, compared by identity, to a value. The map holds each key weakly.
 *
 * <p>{@link java.util.WeakHashMap} compares keys with {@code equals}. Two Clojure values
 * can be equal and still give different request bodies, for example a vector and a list.
 * This map finds a value only for the same key object, so a value never crosses to
 * different data. When the garbage collector clears a key, the next call drops its entry.
 *
 * <p>All methods are synchronized. A value must not hold its own key strongly.
 */
public final class WeakIdentityMap {

    private static final class Key extends WeakReference<Object> {
        final int hash;

        Key(Object referent, ReferenceQueue<Object> queue) {
            super(referent, queue);
            this.hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key)) {
                return false;
            }
            Object mine = get();
            return mine != null && mine == ((Key) other).get();
        }
    }

    /** A lookup key that holds its referent strongly and makes no garbage. */
    private static final class Probe {
        Object referent;
        int hash;

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key && ((Key) other).get() == referent;
        }
    }

    private final HashMap<Object, Object> entries = new HashMap<>();
    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
    private final Probe probe = new Probe();

    private void expunge() {
        Object cleared;
        while ((cleared = queue.poll()) != null) {
            entries.remove(cleared);
        }
    }

    /** The value stored for this exact key object, or null. */
    public synchronized Object get(Object key) {
        if (key == null) {
            return null;
        }
        expunge();
        probe.referent = key;
        probe.hash = System.identityHashCode(key);
        try {
            return entries.get(probe);
        } finally {
            probe.referent = null;
        }
    }

    /** Store {@code value} for this exact key object and return {@code value}. */
    public synchronized Object put(Object key, Object value) {
        if (key == null) {
            return value;
        }
        expunge();
        probe.referent = key;
        probe.hash = System.identityHashCode(key);
        try {
            // A HashMap keeps the stored key when it replaces a value, so an
            // update makes no new weak reference.
            if (entries.containsKey(probe)) {
                entries.put(probe, value);
                return value;
            }
        } finally {
            probe.referent = null;
        }
        entries.put(new Key(key, queue), value);
        return value;
    }

    /** The number of live entries. */
    public synchronized int size() {
        expunge();
        return entries.size();
    }
}
