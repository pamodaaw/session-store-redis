/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.session.store.redis;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.base.MultitenantConstants;
import org.wso2.carbon.identity.application.authentication.framework.store.SessionContextDO;
import org.wso2.carbon.identity.application.authentication.framework.store.SessionDataStore;
import org.wso2.carbon.identity.session.store.redis.config.RedisStoreConfig;
import org.wso2.carbon.identity.session.store.redis.serialize.JavaSessionObjectSerializer;
import org.wso2.carbon.identity.session.store.redis.serialize.SessionObjectSerializer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redis-backed {@link SessionDataStore}. Standalone topology, hybrid-hash value layout, native
 * per-key TTL.
 *
 * <p><b>Pluggable store.</b> This is a concrete peer of the default relational
 * {@code JDBCSessionDataStore} under the {@link SessionDataStore} supertype. The bundle registers it
 * as a {@code SessionDataStore} OSGi service; {@link SessionDataStore#getInstance()} returns it when
 * {@code JDBCPersistenceManager.SessionDataPersist.SessionStoreImplType} is set to
 * {@link RedisConstants#STORE_NAME}.
 *
 * <p><b>Native expiry.</b> Every key is written <em>atomically with its TTL</em> &mdash; there is
 * no write path that produces a key without an expiry, so nothing leaks. Redis evicts expired keys
 * itself, so {@link #removeExpiredSessionData()} is a deliberate no-op and no cleanup thread runs
 * for this backend.
 *
 * <p><b>TTL parity with JDBC.</b> The remaining validity is derived from the inherited
 * {@link SessionDataStore#getValidityPeriodNano(Object, String, int)} &mdash; the same logic the
 * JDBC store uses &mdash; then translated into the Redis key TTL.
 *
 * <p><b>Hybrid hash.</b> The live SSO session ({@code AppAuthFrameworkSessionContextCache}) is a
 * hash carrying queryable metadata + an opaque {@code payload}; other types (auth-flow/temp) are a
 * single serialized string under the {@code authctx} keyspace.
 *
 * <p><b>Tenant resolution.</b> The SPI reads take only {@code (key, type)} but the canonical key
 * scheme embeds {@code tenantId} in every data key. This is bridged by a small, non-sensitive
 * tenant-pointer key ({@code {prefix}:ref:{type}:{id}} &rarr; tenantId, same TTL) written on store
 * and read on lookup. The pointer is scoped by {@code (type, id)} so caches that share an {@code id}
 * value under different types resolve independently.
 */
public class RedisSessionDataStore extends SessionDataStore {

    private static final Logger LOG = LoggerFactory.getLogger(RedisSessionDataStore.class);

    // Atomic "set all hash fields, then set the key TTL" in one server-side step. Never SET then
    // EXPIRE from the client: a crash between them would leak a TTL-less key forever.
    private static final String HSET_PEXPIRE_LUA =
            "redis.call('HSET', KEYS[1], unpack(ARGV, 2)); "
                    + "redis.call('PEXPIRE', KEYS[1], ARGV[1]); "
                    + "return 1";

    private final RedisConnectionManager connectionManager;
    private final RedisKeyBuilder keys;
    private final SessionObjectSerializer serializer;
    private final boolean failClosed;

    public RedisSessionDataStore() {

        this(RedisStoreConfig.fromConfig());
    }

    public RedisSessionDataStore(RedisStoreConfig config) {

        this.connectionManager = new RedisConnectionManager(config);
        this.keys = new RedisKeyBuilder(config.getKeyPrefix());
        this.serializer = new JavaSessionObjectSerializer();
        this.failClosed = config.isFailClosed();
    }

    @Override
    public String getStoreName() {

        return RedisConstants.STORE_NAME;
    }

    // ---------------------------------------------------------------------
    // Session data
    // ---------------------------------------------------------------------

    @Override
    public void storeSessionData(String key, String type, Object entry) {

        storeSessionData(key, type, entry, MultitenantConstants.INVALID_TENANT_ID);
    }

    @Override
    public void storeSessionData(String key, String type, Object entry, int tenantId) {

        guard(() -> {
            long ttlMillis = computeTtlMillis(entry, type, tenantId);
            if (ttlMillis <= 0) {
                // Already expired: persist nothing and remove any prior record.
                clearSessionData(key, type);
                return;
            }
            if (isSessionContextType(type)) {
                writeHybridHash(tenantId, key, entry, ttlMillis);
            } else {
                writeAuthCtxString(tenantId, type, key, entry, ttlMillis);
            }
        });
    }

    @Override
    public Object getSessionData(String key, String type) {

        SessionContextDO dataObject = getSessionContextData(key, type);
        return dataObject == null ? null : dataObject.getEntry();
    }

    @Override
    public SessionContextDO getSessionContextData(String key, String type) {

        return guard(() -> {
            Integer tenantId = lookupTenant(type, key);
            if (tenantId == null) {
                return null; // pointer gone => record missing or expired
            }
            return isSessionContextType(type)
                    ? readHybridHash(tenantId, key)
                    : readAuthCtxString(tenantId, key, type);
        });
    }

    @Override
    public void clearSessionData(String key, String type) {

        guard(() -> {
            Integer tenantId = lookupTenant(type, key);
            if (tenantId == null) {
                return; // already gone -> no-op
            }
            RedisCommands<String, byte[]> redis = connectionManager.sync();
            if (isSessionContextType(type)) {
                // UNLINK frees memory off the main thread for large values.
                redis.unlink(keys.sessionDataKey(tenantId, key));
                // Leave a short-lived LOGGED_OUT marker so a node with a stale local copy that
                // re-checks state invalidates it, closing the race until cluster invalidation
                // propagates.
                redis.set(keys.sessionStateKey(tenantId, key),
                        bytes(RedisConstants.STATE_LOGGED_OUT),
                        SetArgs.Builder.px(RedisConstants.LOGGED_OUT_MARKER_TTL_MILLIS));
            } else {
                redis.unlink(keys.authCtxKey(tenantId, type, key));
            }
            // Bound the pointer's remaining life; reads meanwhile find the data gone -> missing.
            redis.pexpire(keys.refKey(type, key), RedisConstants.LOGGED_OUT_MARKER_TTL_MILLIS);
        });
    }

    @Override
    public void clearSessionDataBatch(List<String> keys, String type) {

        if (keys == null) {
            return;
        }
        for (String key : keys) {
            clearSessionData(key, type);
        }
    }

    @Override
    public void removeSessionData(String key, String type, long nanoTime) {

        // Single key per record: nanoTime is irrelevant. Hard removal (no LOGGED_OUT marker).
        guard(() -> {
            Integer tenantId = lookupTenant(type, key);
            if (tenantId == null) {
                return;
            }
            RedisCommands<String, byte[]> redis = connectionManager.sync();
            if (isSessionContextType(type)) {
                redis.unlink(this.keys.sessionDataKey(tenantId, key),
                        this.keys.sessionStateKey(tenantId, key));
            } else {
                redis.unlink(this.keys.authCtxKey(tenantId, type, key));
            }
            redis.del(this.keys.refKey(type, key));
        });
    }

    @Override
    public void removeExpiredSessionData() {

        // Native TTL: Redis evicts expired keys itself; expiry never walks the keyspace. No-op.
    }

    @Override
    public boolean isSessionLive(String key, String type) {

        return guard(() -> {
            Integer tenantId = lookupTenant(type, key);
            if (tenantId == null) {
                return false;
            }
            String dataKey = isSessionContextType(type)
                    ? keys.sessionDataKey(tenantId, key)
                    : keys.authCtxKey(tenantId, type, key);
            // EXISTS only -> O(1), no deserialization.
            return connectionManager.sync().exists(dataKey) > 0;
        });
    }

    // ---------------------------------------------------------------------
    // Temporary auth-flow data
    // ---------------------------------------------------------------------

    // Temp auth-flow data is written through storeSessionData(key, type, entry, tenantId): a temp
    // type is not the session-context type, so it lands in the authctx keyspace as a single
    // TTL-bearing string, exactly as the JDBC store routes temp records through its store path.

    @Override
    public void removeTempAuthnContextData(String key, String type) {

        guard(() -> {
            Integer tenantId = lookupTenant(type, key);
            if (tenantId == null) {
                return;
            }
            RedisCommands<String, byte[]> redis = connectionManager.sync();
            redis.unlink(keys.authCtxKey(tenantId, type, key));
            redis.del(keys.refKey(type, key));
        });
    }

    // ---------------------------------------------------------------------
    // Write helpers
    // ---------------------------------------------------------------------

    /**
     * Derive the key TTL (ms) from the same validity the JDBC store would use, clamped so sub-ms
     * rounding never yields 0.
     */
    private long computeTtlMillis(Object entry, String type, int tenantId) {

        long ttlMillis = TimeUnit.NANOSECONDS.toMillis(
                getValidityPeriodNano(entry, type, tenantId));
        if (ttlMillis <= 0) {
            return ttlMillis;
        }
        return Math.max(ttlMillis, RedisConstants.MIN_TTL_MILLIS);
    }

    private void writeHybridHash(int tenantId, String sessionId, Object entry, long ttlMillis) {

        Map<String, byte[]> fields = new LinkedHashMap<>();
        fields.put(RedisConstants.FIELD_TENANT_ID, bytes(String.valueOf(tenantId)));
        fields.put(RedisConstants.FIELD_TIME_CREATED, bytes(String.valueOf(System.nanoTime())));
        fields.put(RedisConstants.FIELD_STATE, bytes(RedisConstants.STATE_ACTIVE));
        fields.put(RedisConstants.FIELD_PAYLOAD_VERSION, bytes(RedisConstants.PAYLOAD_VERSION_1));
        fields.put(RedisConstants.FIELD_PAYLOAD, serializer.serialize(entry));

        // ARGV[1] = ttl(ms); ARGV[2..] = field, value, field, value, ...
        List<byte[]> argv = new ArrayList<>();
        argv.add(bytes(String.valueOf(ttlMillis)));
        for (Map.Entry<String, byte[]> f : fields.entrySet()) {
            argv.add(bytes(f.getKey()));
            argv.add(f.getValue());
        }
        RedisCommands<String, byte[]> redis = connectionManager.sync();
        redis.eval(HSET_PEXPIRE_LUA, ScriptOutputType.INTEGER,
                new String[]{keys.sessionDataKey(tenantId, sessionId)},
                argv.toArray(new byte[0][]));

        redis.set(keys.sessionStateKey(tenantId, sessionId), bytes(RedisConstants.STATE_ACTIVE),
                SetArgs.Builder.px(ttlMillis));
        writePointer(RedisConstants.TYPE_SESSION_CONTEXT, sessionId, tenantId, ttlMillis);
    }

    private void writeAuthCtxString(int tenantId, String type, String contextId, Object entry,
                                    long ttlMillis) {

        // Single SET ... PX: value and TTL land together, atomically.
        connectionManager.sync().set(keys.authCtxKey(tenantId, type, contextId),
                serializer.serialize(entry), SetArgs.Builder.px(ttlMillis));
        writePointer(type, contextId, tenantId, ttlMillis);
    }

    private void writePointer(String type, String id, int tenantId, long ttlMillis) {

        connectionManager.sync().set(keys.refKey(type, id), bytes(String.valueOf(tenantId)),
                SetArgs.Builder.px(ttlMillis));
    }

    // ---------------------------------------------------------------------
    // Read helpers
    // ---------------------------------------------------------------------

    private SessionContextDO readHybridHash(int tenantId, String sessionId) {

        Map<String, byte[]> hash = connectionManager.sync()
                .hgetall(keys.sessionDataKey(tenantId, sessionId));
        if (hash == null || hash.isEmpty()) {
            return null; // expired/missing -> Redis lazily drops the key; a read never sees it
        }
        Object entry = serializer.deserialize(hash.get(RedisConstants.FIELD_PAYLOAD));
        long timeCreated = parseLong(hash.get(RedisConstants.FIELD_TIME_CREATED));
        return new SessionContextDO(sessionId, RedisConstants.TYPE_SESSION_CONTEXT, entry,
                timeCreated, tenantId);
    }

    private SessionContextDO readAuthCtxString(int tenantId, String contextId, String type) {

        byte[] value = connectionManager.sync().get(keys.authCtxKey(tenantId, type, contextId));
        if (value == null) {
            return null;
        }
        return new SessionContextDO(contextId, type, serializer.deserialize(value), 0L, tenantId);
    }

    private Integer lookupTenant(String type, String id) {

        byte[] value = connectionManager.sync().get(keys.refKey(type, id));
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(new String(value, StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Misc
    // ---------------------------------------------------------------------

    private static boolean isSessionContextType(String type) {

        return RedisConstants.TYPE_SESSION_CONTEXT.equals(type);
    }

    private static byte[] bytes(String s) {

        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static long parseLong(byte[] value) {

        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(new String(value, StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Translates any Redis/Lettuce failure into the unchecked {@link RedisSessionStoreException},
     * carrying the configured fail-closed flag. {@link RedisSessionStoreException}s thrown by the
     * serializer are propagated unchanged.
     */
    private <T> T guard(Supplier<T> op) {

        try {
            return op.get();
        } catch (RedisSessionStoreException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RedisSessionStoreException("Redis session-store command failed.", e, failClosed);
        }
    }

    private void guard(Runnable op) {

        guard(() -> {
            op.run();
            return null;
        });
    }

    /**
     * Releases the underlying Lettuce client/connection. Invoked by the framework when it stops the
     * active store ({@link SessionDataStore#getInstance()}.stopService()). Delegates to
     * {@link #close()}; safe to call more than once.
     */
    @Override
    public void stopService() {

        close();
    }

    /**
     * Releases the underlying Lettuce client/connection. Called on bundle deactivation.
     */
    public void close() {

        connectionManager.close();
    }
}
