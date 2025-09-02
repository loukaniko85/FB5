package net.filebot;

import static java.util.stream.Collectors.*;
import static net.filebot.Logging.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

/**
 * High-performance cache implementation using Caffeine
 * Replaces the older EhCache implementation with:
 * - Better memory efficiency
 * - Faster access patterns
 * - Async loading capabilities
 * - Built-in statistics
 * - Reduced GC pressure
 */
public class OptimizedCache<K, V> {

    private final Cache<K, V> cache;
    private final String name;
    private final CacheType cacheType;
    private final Duration expirationTime;

    public OptimizedCache(String name, CacheType cacheType, Duration expirationTime) {
        this.name = name;
        this.cacheType = cacheType;
        this.expirationTime = expirationTime;
        
        // Build optimized cache configuration
        Caffeine<Object, Object> builder = Caffeine.newBuilder()
            .maximumSize(getMaxSize(cacheType))
            .expireAfterWrite(expirationTime)
            .recordStats()
            .softValues(); // Use soft references for better memory management
        
        // Add specific configurations based on cache type
        switch (cacheType) {
            case Persistent:
                builder.maximumWeight(100 * 1024 * 1024); // 100MB limit for persistent cache
                break;
            case Temporary:
                builder.maximumSize(1000); // Limit temporary cache to 1000 entries
                break;
            case Memory:
                builder.maximumSize(500); // Limit memory cache to 500 entries
                break;
        }
        
        this.cache = builder.build();
    }

    private long getMaxSize(CacheType type) {
        switch (type) {
            case Persistent: return 10000;
            case Temporary: return 5000;
            case Memory: return 1000;
            default: return 1000;
        }
    }

    public V get(K key) {
        try {
            return cache.getIfPresent(key);
        } catch (Exception e) {
            debug.warning(format("Cache get: %s => %s", key, e));
            return null;
        }
    }

    public V get(K key, Function<K, V> loader) {
        try {
            return cache.get(key, loader);
        } catch (Exception e) {
            debug.warning(format("Cache get with loader: %s => %s", key, e));
            return null;
        }
    }

    public V computeIfAbsent(K key, Function<K, V> compute) {
        try {
            return cache.get(key, compute);
        } catch (Exception e) {
            debug.warning(format("Cache computeIfAbsent: %s => %s", key, e));
            return null;
        }
    }

    public CompletableFuture<V> getAsync(K key, Function<K, V> loader, Executor executor) {
        return CompletableFuture.supplyAsync(() -> get(key, loader), executor);
    }

    public void put(K key, V value) {
        try {
            cache.put(key, value);
        } catch (Exception e) {
            debug.warning(format("Cache put: %s => %s", key, e));
        }
    }

    public void putAll(java.util.Map<K, V> map) {
        try {
            cache.putAll(map);
        } catch (Exception e) {
            debug.warning(format("Cache putAll: %s => %s", map.size(), e));
        }
    }

    public void remove(K key) {
        try {
            cache.invalidate(key);
        } catch (Exception e) {
            debug.warning(format("Cache remove: %s => %s", key, e));
        }
    }

    public void clear() {
        try {
            cache.invalidateAll();
        } catch (Exception e) {
            debug.warning(format("Cache clear: %s => %s", name, e));
        }
    }

    public void flush() {
        // Caffeine doesn't need explicit flushing, but we can trigger cleanup
        cache.cleanUp();
    }

    public long size() {
        return cache.estimatedSize();
    }

    public CacheStats getStats() {
        return cache.stats();
    }

    public String getName() {
        return name;
    }

    public CacheType getCacheType() {
        return cacheType;
    }

    public Duration getExpirationTime() {
        return expirationTime;
    }

    @Override
    public String toString() {
        return String.format("OptimizedCache[name=%s, type=%s, size=%d, stats=%s]", 
            name, cacheType, size(), getStats());
    }

    /**
     * Create a typed cache wrapper for better type safety
     */
    public <T> TypedCache<T> cast(Class<T> cls) {
        return new TypedCache<>(this, cls::cast, cls::cast);
    }

    /**
     * Create a list-typed cache wrapper
     */
    public <T> TypedCache<java.util.List<T>> castList(Class<T> cls) {
        return new TypedCache<>(this, 
            obj -> obj == null ? null : ((java.util.List<?>) obj).stream().map(cls::cast).collect(toList()),
            obj -> obj);
    }

    /**
     * Typed cache wrapper for better type safety
     */
    public static class TypedCache<T> {
        private final OptimizedCache<Object, Object> delegate;
        private final Function<Object, T> read;
        private final Function<T, Object> write;

        public TypedCache(OptimizedCache<Object, Object> delegate, Function<Object, T> read, Function<T, Object> write) {
            this.delegate = delegate;
            this.read = read;
            this.write = write;
        }

        @SuppressWarnings("unchecked")
        public T get(Object key) {
            return (T) delegate.get(key);
        }

        @SuppressWarnings("unchecked")
        public T get(Object key, Function<Object, T> loader) {
            return (T) delegate.get(key, k -> write.apply(loader.apply(k)));
        }

        @SuppressWarnings("unchecked")
        public T computeIfAbsent(Object key, Function<Object, T> compute) {
            return (T) delegate.computeIfAbsent(key, k -> write.apply(compute.apply(k)));
        }

        public void put(Object key, T value) {
            delegate.put(key, write.apply(value));
        }

        public void remove(Object key) {
            delegate.remove(key);
        }

        public void clear() {
            delegate.clear();
        }

        public long size() {
            return delegate.size();
        }

        public String getName() {
            return delegate.getName();
        }

        public CacheType getCacheType() {
            return delegate.getCacheType();
        }
    }

    /**
     * Predicate for checking if cache entries are stale
     */
    public static <K> Predicate<CacheEntry<K>> isStale(Duration expirationTime) {
        return entry -> System.currentTimeMillis() - entry.getTimestamp() > expirationTime.toMillis();
    }

    /**
     * Simple cache entry wrapper
     */
    public static class CacheEntry<K> {
        private final K key;
        private final long timestamp;
        private final Object value;

        public CacheEntry(K key, Object value) {
            this.key = key;
            this.value = value;
            this.timestamp = System.currentTimeMillis();
        }

        public K getKey() { return key; }
        public Object getValue() { return value; }
        public long getTimestamp() { return timestamp; }
    }
}