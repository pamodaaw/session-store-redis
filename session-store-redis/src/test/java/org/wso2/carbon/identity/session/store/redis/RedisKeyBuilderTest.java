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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the canonical key scheme (design §4.2): tenant in every key, the {@code :data}/
 * {@code :state} sub-keys, and the Cluster hash tag around the session id.
 */
class RedisKeyBuilderTest {

    private final RedisKeyBuilder keys = new RedisKeyBuilder("wso2is");

    @Test
    void sessionKeysAreTenantScopedAndHashTagged() {

        assertEquals("wso2is:1:session:{abc}:data", keys.sessionDataKey(1, "abc"));
        assertEquals("wso2is:1:session:{abc}:state", keys.sessionStateKey(1, "abc"));
    }

    @Test
    void perSessionKeysShareOneHashSlot() {

        // The {sessionId} hash tag must be identical for :data and :state so Cluster keeps them
        // co-located in one slot.
        String data = keys.sessionDataKey(42, "sid");
        String state = keys.sessionStateKey(42, "sid");
        assertEquals(hashTag(data), hashTag(state));
        assertEquals("sid", hashTag(data));
    }

    @Test
    void authCtxAndRefKeysIncludeType() {

        assertEquals("wso2is:5:authctx:SessionDataCache:ctx-1",
                keys.authCtxKey(5, "SessionDataCache", "ctx-1"));
        assertEquals("wso2is:ref:SessionDataCache:ctx-1",
                keys.refKey("SessionDataCache", "ctx-1"));
    }

    @Test
    void sameIdUnderDifferentTypesYieldsDistinctKeys() {

        // The composite (key, type) is the logical primary key: the same id under two cache types
        // must map to different Redis keys, or the two caches collide (last-write-wins).
        String sessionDataCacheKey = keys.authCtxKey(1, "SessionDataCache", "shared-id");
        String authResultCacheKey = keys.authCtxKey(1, "AuthenticationResultCache", "shared-id");
        assertNotEquals(sessionDataCacheKey, authResultCacheKey);

        assertNotEquals(keys.refKey("SessionDataCache", "shared-id"),
                keys.refKey("AuthenticationResultCache", "shared-id"));
    }

    @Test
    void prefixIsHonoured() {

        RedisKeyBuilder custom = new RedisKeyBuilder("acme-prod");
        assertTrue(custom.sessionDataKey(1, "x").startsWith("acme-prod:1:session:"));
    }

    private static String hashTag(String key) {

        int open = key.indexOf('{');
        int close = key.indexOf('}', open);
        return key.substring(open + 1, close);
    }
}
