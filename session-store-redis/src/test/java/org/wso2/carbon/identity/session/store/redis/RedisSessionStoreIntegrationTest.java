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

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.wso2.carbon.identity.application.authentication.framework.internal.FrameworkServiceDataHolder;
import org.wso2.carbon.identity.application.authentication.framework.store.JavaSessionSerializer;
import org.wso2.carbon.identity.application.authentication.framework.store.SessionContextDO;
import org.wso2.carbon.identity.core.cache.CacheEntry;
import org.wso2.carbon.identity.session.store.redis.config.RedisStoreConfig;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for {@link RedisSessionDataStore} against a real Redis (Testcontainers),
 * exercising the framework {@code SessionStore} SPI and the design's TTL / keyspace invariants.
 * Skipped (not failed) when Docker is unavailable so the build stays green (SPEC §9).
 *
 * <p>Every store write must be atomic-with-TTL (no key without an expiry), tenant-scoped, and
 * routed through the framework's serializer so the Redis wire format matches the JDBC blob path.
 * These tests assert those guarantees directly against the keyspace.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisSessionStoreIntegrationTest {

    private static final String TYPE_SESSION = "AppAuthFrameworkSessionContextCache";
    private static final String TYPE_AUTHCTX = "AuthenticationContextCache";
    private static final int TENANT = 1;
    private static final String PREFIX = "wso2is";

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    private RedisSessionDataStore store;
    private RedisClient inspectClient;
    private StatefulRedisConnection<String, String> inspect;

    @BeforeEach
    void setUp() {

        String hostPort = REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
        store = new RedisSessionDataStore(new RedisStoreConfig.Builder().hosts(hostPort).build());
        inspectClient = RedisClient.create("redis://" + hostPort);
        inspect = inspectClient.connect();
    }

    @AfterEach
    void tearDown() {

        if (store != null) {
            store.close();
        }
        if (inspect != null) {
            inspect.close();
        }
        if (inspectClient != null) {
            inspectClient.shutdown();
        }
    }

    // ------------------------------------------------------------------ SSO session context ----

    @Test
    void roundTripAndEveryKeyCarriesATtl() {

        String sid = "sess-rt";
        store.storeSessionData(sid, TYPE_SESSION, entry("hello", TimeUnit.MINUTES.toNanos(10)), TENANT);

        Object read = store.getSessionData(sid, TYPE_SESSION);
        assertNotNull(read);
        assertEquals("hello", ((TestEntry) read).getPayload());
        assertTrue(store.isSessionLive(sid, TYPE_SESSION));

        RedisCommands<String, String> redis = inspect.sync();
        for (String key : new String[]{
                dataKey(TENANT, sid), stateKey(TENANT, sid), refKey(sid)}) {
            assertEquals(1L, redis.exists(key), "expected key to exist: " + key);
            assertTrue(redis.ttl(key) >= 0, "every key must carry a TTL (never -1): " + key);
        }
    }

    @Test
    void hybridHashCarriesQueryableMetadataAndOpaquePayload() {

        String sid = "sess-hash";
        store.storeSessionData(sid, TYPE_SESSION, entry("v", TimeUnit.MINUTES.toNanos(10)), TENANT);

        RedisCommands<String, String> redis = inspect.sync();
        String data = dataKey(TENANT, sid);
        assertEquals("1", redis.hget(data, "tenantId"));
        assertEquals("ACTIVE", redis.hget(data, "state"));
        assertEquals("1", redis.hget(data, "payloadVersion"));
        assertTrue(redis.hexists(data, "timeCreated"), "timeCreated metadata must be present");
        assertTrue(redis.hexists(data, "payload"), "opaque payload field must be present");
    }

    @Test
    void getSessionContextDataReturnsTenantAndCreationMetadata() {

        String sid = "sess-meta";
        int tenant = 2;
        store.storeSessionData(sid, TYPE_SESSION, entry("v", TimeUnit.MINUTES.toNanos(10)), tenant);

        SessionContextDO dataObject = store.getSessionContextData(sid, TYPE_SESSION);
        assertNotNull(dataObject);
        assertEquals(tenant, dataObject.getTenantId());
        assertEquals(TYPE_SESSION, dataObject.getType());
        assertTrue(dataObject.getNanoTime() > 0, "timeCreated should be populated for a session ctx");
        // Tenant-scoped key path (SPEC §8 tenant isolation).
        assertEquals(1L, inspect.sync().exists(dataKey(tenant, sid)));
    }

    @Test
    void reStoringSlidesTheTtlAndUpdatesThePayload() {

        String sid = "sess-slide";
        store.storeSessionData(sid, TYPE_SESSION, entry("first", TimeUnit.MINUTES.toNanos(5)), TENANT);
        long firstTtl = inspect.sync().pttl(dataKey(TENANT, sid));

        store.storeSessionData(sid, TYPE_SESSION, entry("second", TimeUnit.MINUTES.toNanos(30)), TENANT);
        long secondTtl = inspect.sync().pttl(dataKey(TENANT, sid));

        assertTrue(secondTtl > firstTtl, "re-store with a longer validity must extend the TTL");
        assertEquals("second", ((TestEntry) store.getSessionData(sid, TYPE_SESSION)).getPayload());
    }

    @Test
    void subSecondValidityIsFlooredSoItNeverRoundsToNoExpiry() {

        String sid = "sess-floor";
        // 300ms validity would round toward 0; the store floors it to MIN_TTL_MILLIS (1000ms).
        store.storeSessionData(sid, TYPE_SESSION, entry("v", TimeUnit.MILLISECONDS.toNanos(300)), TENANT);

        long pttl = inspect.sync().pttl(dataKey(TENANT, sid));
        assertTrue(pttl > 400, "floor must lift a sub-second TTL above its raw value, was " + pttl);
        assertTrue(pttl <= RedisConstants.MIN_TTL_MILLIS,
                "floored TTL must not exceed MIN_TTL_MILLIS, was " + pttl);
    }

    @Test
    void alreadyExpiredEntryPersistsNothing() {

        String sid = "sess-dead";
        // A negative validity means "already expired": the store must persist nothing (SPEC §5.6).
        store.storeSessionData(sid, TYPE_SESSION, entry("v", -TimeUnit.SECONDS.toNanos(1)), TENANT);

        assertNull(store.getSessionData(sid, TYPE_SESSION));
        assertFalse(store.isSessionLive(sid, TYPE_SESSION));
        assertEquals(0L, inspect.sync().exists(dataKey(TENANT, sid)));
    }

    @Test
    void keyDisappearsAfterTtlElapses() throws InterruptedException {

        String sid = "sess-expire";
        store.storeSessionData(sid, TYPE_SESSION, entry("v", TimeUnit.MILLISECONDS.toNanos(1200)), TENANT);
        assertTrue(store.isSessionLive(sid, TYPE_SESSION));

        TimeUnit.MILLISECONDS.sleep(1600);

        assertNull(store.getSessionData(sid, TYPE_SESSION));
        assertFalse(store.isSessionLive(sid, TYPE_SESSION));
    }

    // --------------------------------------------------------------------------- Logout paths ----

    @Test
    void logoutDeletesDataAndLeavesLoggedOutMarker() {

        String sid = "sess-logout";
        store.storeSessionData(sid, TYPE_SESSION, entry("v", TimeUnit.MINUTES.toNanos(10)), TENANT);
        store.clearSessionData(sid, TYPE_SESSION);

        RedisCommands<String, String> redis = inspect.sync();
        assertEquals(0L, redis.exists(dataKey(TENANT, sid)));
        assertEquals("LOGGED_OUT", redis.get(stateKey(TENANT, sid)));
        assertFalse(store.isSessionLive(sid, TYPE_SESSION), "a cleared session must not read as live");
    }

    @Test
    void loggedOutMarkerIsShortLived() {

        String sid = "sess-logout-ttl";
        store.storeSessionData(sid, TYPE_SESSION, entry("v", TimeUnit.MINUTES.toNanos(10)), TENANT);
        store.clearSessionData(sid, TYPE_SESSION);

        long markerTtl = inspect.sync().pttl(stateKey(TENANT, sid));
        assertTrue(markerTtl > 0 && markerTtl <= RedisConstants.LOGGED_OUT_MARKER_TTL_MILLIS,
                "LOGGED_OUT marker must carry a short bounded TTL, was " + markerTtl);
    }

    @Test
    void hardRemoveLeavesNoTraceAndNoMarker() {

        String sid = "sess-hard-remove";
        store.storeSessionData(sid, TYPE_SESSION, entry("v", TimeUnit.MINUTES.toNanos(10)), TENANT);
        store.removeSessionData(sid, TYPE_SESSION, System.nanoTime());

        RedisCommands<String, String> redis = inspect.sync();
        assertEquals(0L, redis.exists(dataKey(TENANT, sid)), "data must be gone");
        assertEquals(0L, redis.exists(stateKey(TENANT, sid)), "hard remove leaves no LOGGED_OUT marker");
        assertEquals(0L, redis.exists(refKey(sid)), "tenant pointer must be deleted");
    }

    @Test
    void batchClearMarksEverySessionLoggedOut() {

        List<String> sids = Arrays.asList("sess-b1", "sess-b2", "sess-b3");
        for (String sid : sids) {
            store.storeSessionData(sid, TYPE_SESSION, entry("v", TimeUnit.MINUTES.toNanos(10)), TENANT);
        }
        store.clearSessionDataBatch(sids, TYPE_SESSION);

        RedisCommands<String, String> redis = inspect.sync();
        for (String sid : sids) {
            assertEquals(0L, redis.exists(dataKey(TENANT, sid)), "data gone for " + sid);
            assertEquals("LOGGED_OUT", redis.get(stateKey(TENANT, sid)), "marker set for " + sid);
        }
    }

    // ------------------------------------------------------- Temporary auth-flow (authctx) data ----

    @Test
    void temporaryAuthnContextRoundTripAndTtl() {

        String ctxId = "ctx-temp";
        store.storeSessionData(ctxId, TYPE_AUTHCTX, entry("state", TimeUnit.MINUTES.toNanos(5)),
                TENANT);

        Object read = store.getSessionData(ctxId, TYPE_AUTHCTX);
        assertNotNull(read);
        assertEquals("state", ((TestEntry) read).getPayload());

        RedisCommands<String, String> redis = inspect.sync();
        assertEquals(1L, redis.exists(authCtxKey(TENANT, ctxId)));
        assertTrue(redis.ttl(authCtxKey(TENANT, ctxId)) >= 0, "authctx key must carry a TTL");
        assertTrue(store.isSessionLive(ctxId, TYPE_AUTHCTX));
    }

    @Test
    void removingTemporaryAuthnContextDeletesItHard() {

        String ctxId = "ctx-temp-remove";
        store.storeSessionData(ctxId, TYPE_AUTHCTX, entry("state", TimeUnit.MINUTES.toNanos(5)),
                TENANT);
        store.removeTempAuthnContextData(ctxId, TYPE_AUTHCTX);

        assertNull(store.getSessionData(ctxId, TYPE_AUTHCTX));
        RedisCommands<String, String> redis = inspect.sync();
        assertEquals(0L, redis.exists(authCtxKey(TENANT, ctxId)));
        assertEquals(0L, redis.exists(refKey(ctxId)));
    }

    @Test
    void nonSessionTypeIsStoredUnderAuthctxKeyspace() {

        String ctxId = "ctx-via-store";
        store.storeSessionData(ctxId, TYPE_AUTHCTX, entry("blob", TimeUnit.MINUTES.toNanos(5)), TENANT);

        SessionContextDO dataObject = store.getSessionContextData(ctxId, TYPE_AUTHCTX);
        assertNotNull(dataObject);
        assertEquals(TYPE_AUTHCTX, dataObject.getType());
        // authctx records land under the authctx keyspace, not the session hash.
        RedisCommands<String, String> redis = inspect.sync();
        assertEquals(1L, redis.exists(authCtxKey(TENANT, ctxId)));
        assertEquals(0L, redis.exists(dataKey(TENANT, ctxId)));
    }

    @Test
    void readingAnAbsentSessionReturnsNullNotAnError() {

        assertNull(store.getSessionData("never-stored", TYPE_SESSION));
        assertNull(store.getSessionContextData("never-stored", TYPE_SESSION));
        assertFalse(store.isSessionLive("never-stored", TYPE_SESSION));
    }

    // ------------------------------------------------------------------------------- helpers ----

    private static String dataKey(int tenant, String sid) {

        return PREFIX + ":" + tenant + ":session:{" + sid + "}:data";
    }

    private static String stateKey(int tenant, String sid) {

        return PREFIX + ":" + tenant + ":session:{" + sid + "}:state";
    }

    private static String authCtxKey(int tenant, String ctxId) {

        return PREFIX + ":" + tenant + ":authctx:" + ctxId;
    }

    private static String refKey(String id) {

        return PREFIX + ":ref:" + id;
    }

    private static TestEntry entry(String payload, long validityNano) {

        TestEntry e = new TestEntry(payload);
        e.setValidityPeriod(validityNano);
        return e;
    }

    /** Minimal serializable {@link CacheEntry} so TTL derivation uses the entry's own validity. */
    static class TestEntry extends CacheEntry {

        private static final long serialVersionUID = 1L;
        private final String payload;

        TestEntry(String payload) {

            this.payload = payload;
        }

        String getPayload() {

            return payload;
        }
    }
}
