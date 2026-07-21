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
 *   {prefix}:{tenantId}:session:{&lt;sessionId&gt;}:data      Hash   (live SSO session, hybrid hash)
 *   {prefix}:{tenantId}:session:{&lt;sessionId&gt;}:state     String (ACTIVE | LOGGED_OUT)
 *   {prefix}:{tenantId}:authctx:{type}:{contextId}         String (non-session-context caches)
 *   {prefix}:ref:{type}:{id}                               String (id -&gt; tenantId pointer)
 * </pre>
 *
 * <p>{@code tenantId} is in <b>every</b> data key to satisfy tenant isolation (SPEC §8). The session
 * id is wrapped in a Redis Cluster <b>hash tag</b> ({@code {...}}) so the per-session {@code :data}
 * and {@code :state} keys land in the same hash slot &mdash; multi-key/transaction ops stay on one
 * node (design §4.2).
 *
 * <p><b>{@code type} is part of the key.</b> The framework's logical primary key is the composite
 * {@code (key, type)} (the JDBC store binds both columns), so two caches may legitimately use the
 * same {@code key} value under different {@code type}s (e.g. {@code SessionDataCache} and
 * {@code AuthenticationResultCache} share a session-data key). Folding {@code type} into the authctx
 * and ref keys keeps those distinct in Redis; omitting it made them collide (last-write-wins) and a
 * read for one type returned another type's entry &rarr; {@code ClassCastException}.
 *
 * <p><b>Delimiter safety.</b> {@code type} values are Carbon cache names (e.g.
 * {@code AppAuthFrameworkSessionContextCache}, {@code SessionDataCache}) &mdash; identifier-like and
 * free of the {@code ':'} delimiter, so they are safe to embed unescaped. {@code key}/{@code id}
 * remain the final segment of every key, so a {@code ':'} inside them cannot shift a boundary.
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

    /** {@code {prefix}:{tenantId}:authctx:{type}:{contextId}} */
    String authCtxKey(int tenantId, String type, String contextId) {

        return prefix + ":" + tenantId + ":" + RedisConstants.SEG_AUTHCTX + ":" + type + ":"
                + contextId;
    }

    /**
     * {@code {prefix}:ref:{type}:{id}} &mdash; the tenant-pointer key ({@code (type, id)} &rarr;
     * tenantId). Scoped by {@code type} because the same {@code id} legitimately exists under
     * multiple types, but <b>not</b> tenant-scoped (the lookup that resolves the tenant cannot
     * itself be tenant-scoped); carries only a tenant id, never session payload. See
     * {@code RedisSessionDataStore} tenant-resolution note.
     */
    String refKey(String type, String id) {

        return prefix + ":ref:" + type + ":" + id;
    }

    // The hash tag {sessionId} forces all per-session sub-keys into one Cluster slot.
    private String sessionBase(int tenantId, String sessionId) {

        return prefix + ":" + tenantId + ":" + RedisConstants.SEG_SESSION + ":{" + sessionId + "}";
    }
}
