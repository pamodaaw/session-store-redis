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

import org.junit.jupiter.api.Test;
import org.wso2.carbon.identity.session.store.redis.config.RedisStoreConfig;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Construction-time behaviour of {@link RedisSessionDataStore} that needs no live Redis:
 * <ul>
 *   <li>Sentinel / Cluster topologies are explicitly unsupported in this MVP and must fail fast
 *       (design §5.6, §8.1) rather than silently misbehave.</li>
 *   <li>An unreachable Redis surfaces a {@link RedisSessionStoreException} that carries the
 *       configured failure signal &mdash; fail-closed by default, fail-degraded only when opted
 *       in (design §9.2 / SPEC §8).</li>
 * </ul>
 */
class RedisSessionDataStoreConstructionTest {

    @Test
    void sentinelTopologyIsRejectedInThisMvp() {

        RedisStoreConfig config = new RedisStoreConfig.Builder()
                .mode(RedisConstants.MODE_SENTINEL)
                .build();

        assertThrows(UnsupportedOperationException.class, () -> new RedisSessionDataStore(config));
    }

    @Test
    void clusterTopologyIsRejectedInThisMvp() {

        RedisStoreConfig config = new RedisStoreConfig.Builder()
                .mode(RedisConstants.MODE_CLUSTER)
                .build();

        assertThrows(UnsupportedOperationException.class, () -> new RedisSessionDataStore(config));
    }

    @Test
    void unreachableRedisFailsClosedByDefault() throws IOException {

        RedisStoreConfig config = new RedisStoreConfig.Builder()
                .hosts("127.0.0.1:" + unusedPort())
                .commandTimeoutMs(200L)
                .build();

        RedisSessionStoreException ex =
                assertThrows(RedisSessionStoreException.class, () -> new RedisSessionDataStore(config));
        // Default policy must be fail-closed: the outage becomes "not authenticated".
        assertTrue(ex.isFailClosed(), "an unreachable Redis must fail closed by default");
    }

    @Test
    void unreachableRedisCarriesTheFailDegradedSignalWhenConfigured() throws IOException {

        RedisStoreConfig config = new RedisStoreConfig.Builder()
                .hosts("127.0.0.1:" + unusedPort())
                .commandTimeoutMs(200L)
                .failureMode(RedisConstants.FAILURE_MODE_FAIL_DEGRADED)
                .build();

        RedisSessionStoreException ex =
                assertThrows(RedisSessionStoreException.class, () -> new RedisSessionDataStore(config));
        assertFalse(ex.isFailClosed(),
                "fail_degraded must be propagated so the framework can ride out the outage");
        // The store name is stable regardless of connectivity (used for provider selection).
        assertEquals("Redis", RedisConstants.STORE_NAME);
    }

    /** Reserve then release a port so a connect to it is reliably refused. */
    private static int unusedPort() throws IOException {

        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
