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

package org.wso2.carbon.identity.session.store.redis.config;

import org.junit.jupiter.api.Test;
import org.wso2.carbon.identity.session.store.redis.RedisConstants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RedisStoreConfig}: the documented defaults, builder overrides, and the
 * fail-closed/fail-degraded decision that carries the security-relevant failure signal (design
 * §9.2). No Redis or Docker is exercised &mdash; this covers config-object behaviour only.
 */
class RedisStoreConfigTest {

    @Test
    void defaultsMatchTheDocumentedDefaults() {

        RedisStoreConfig config = new RedisStoreConfig.Builder().build();

        assertEquals(RedisConstants.DEFAULT_MODE, config.getMode());
        assertEquals(RedisConstants.DEFAULT_HOSTS, config.getHosts());
        assertEquals(RedisConstants.DEFAULT_DATABASE, config.getDatabase());
        assertEquals(RedisConstants.DEFAULT_SSL_ENABLED, config.isSslEnabled());
        assertEquals(RedisConstants.DEFAULT_CONNECTION_TIMEOUT_MS, config.getConnectionTimeoutMs());
        assertEquals(RedisConstants.DEFAULT_COMMAND_TIMEOUT_MS, config.getCommandTimeoutMs());
        assertEquals(RedisConstants.DEFAULT_KEY_PREFIX, config.getKeyPrefix());
        assertEquals(RedisConstants.DEFAULT_SERIALIZER, config.getSerializer());
    }

    @Test
    void defaultFailureModeIsFailClosed() {

        // The default must fail closed: a Redis outage means "not authenticated", never a silent
        // degrade (design §9.2 / SPEC §8).
        assertTrue(new RedisStoreConfig.Builder().build().isFailClosed());
    }

    @Test
    void builderOverridesAreHonoured() {

        RedisStoreConfig config = new RedisStoreConfig.Builder()
                .mode("standalone")
                .hosts("redis.internal:6380")
                .password("s3cret")
                .database(3)
                .keyPrefix("acme-prod")
                .commandTimeoutMs(250L)
                .build();

        assertEquals("standalone", config.getMode());
        assertEquals("redis.internal:6380", config.getHosts());
        assertEquals("s3cret", config.getPassword());
        assertEquals(3, config.getDatabase());
        assertEquals("acme-prod", config.getKeyPrefix());
        assertEquals(250L, config.getCommandTimeoutMs());
    }

    @Test
    void failDegradedModeIsNotFailClosed() {

        RedisStoreConfig config = new RedisStoreConfig.Builder()
                .failureMode(RedisConstants.FAILURE_MODE_FAIL_DEGRADED)
                .build();

        assertFalse(config.isFailClosed());
    }

    @Test
    void failClosedModeIsFailClosed() {

        RedisStoreConfig config = new RedisStoreConfig.Builder()
                .failureMode(RedisConstants.FAILURE_MODE_FAIL_CLOSED)
                .build();

        assertTrue(config.isFailClosed());
    }

    @Test
    void unknownFailureModeFailsClosed() {

        // Anything that is not explicitly "fail_degraded" must fall back to the safe default.
        RedisStoreConfig config = new RedisStoreConfig.Builder()
                .failureMode("something-else")
                .build();

        assertTrue(config.isFailClosed());
    }

    @Test
    void passwordDefaultsToEmptyWhenUnset() {

        // With no identity.xml value in a unit-test JVM, the password is empty (no AUTH attempted).
        assertEquals("", RedisStoreConfig.fromConfig().getPassword());
    }
}
