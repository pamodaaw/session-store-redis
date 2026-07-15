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

/**
 * Builds Redis keys per the canonical, tenant-inclusive key scheme (design §4.2).
 *
 * <pre>
 *   {prefix}:{tenantId}:session:{&lt;sessionId&gt;}:data    Hash   (live SSO session, hybrid hash)
 *   {prefix}:{tenantId}:session:{&lt;sessionId&gt;}:state   String (ACTIVE | LOGGED_OUT)
 *   {prefix}:{tenantId}:authctx:{contextId}              String (temporary auth-flow context)
 * </pre>
 *
 * <p>{@code tenantId} is in <b>every</b> key to satisfy tenant isolation (SPEC §8). The session id
 * is wrapped in a Redis Cluster <b>hash tag</b> ({@code {...}}) so the per-session {@code :data}
 * and {@code :state} keys land in the same hash slot &mdash; multi-key/transaction ops stay on one
 * node (design §4.2).
 */
final class RedisKeyBuilder {

    private final String prefix;

    RedisKeyBuilder(String prefix) {

        this.prefix = prefix;
    }

    /** {@code {prefix}:{tenantId}:session:{<sessionId>}:data} */
    String sessionDataKey(int tenantId, String sessionId) {

        return sessionBase(tenantId, sessionId) + ":" + RedisConstants.SUBKEY_DATA;
    }

    /** {@code {prefix}:{tenantId}:session:{<sessionId>}:state} */
    String sessionStateKey(int tenantId, String sessionId) {

        return sessionBase(tenantId, sessionId) + ":" + RedisConstants.SUBKEY_STATE;
    }

    /** {@code {prefix}:{tenantId}:authctx:{contextId}} */
    String authCtxKey(int tenantId, String contextId) {

        return prefix + ":" + tenantId + ":" + RedisConstants.SEG_AUTHCTX + ":" + contextId;
    }

    /**
     * {@code {prefix}:ref:{id}} &mdash; the tenant-pointer key (id &rarr; tenantId). Not
     * tenant-scoped (the lookup that resolves tenant cannot itself be tenant-scoped); carries only
     * a tenant id, never session payload. See {@code RedisSessionDataStore} tenant-resolution note.
     */
    String refKey(String id) {

        return prefix + ":ref:" + id;
    }

    // The hash tag {sessionId} forces all per-session sub-keys into one Cluster slot.
    private String sessionBase(int tenantId, String sessionId) {

        return prefix + ":" + tenantId + ":" + RedisConstants.SEG_SESSION + ":{" + sessionId + "}";
    }
}
