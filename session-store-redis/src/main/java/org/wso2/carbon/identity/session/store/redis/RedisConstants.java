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
 * Config keys, key-prefix defaults, hybrid-hash field names and tuning constants for the Redis
 * store. Config keys resolve under the first-class {@code <RedisSessionPersistenceManager>} element
 * in {@code identity.xml} (i.e. {@code RedisSessionPersistenceManager.<Name>}).
 */
public final class RedisConstants {

    private RedisConstants() {

    }

    /** The {@code getStoreName()} this backend registers under; matches {@code store_impl_type}. */
    public static final String STORE_NAME = "Redis";

    // ---- Config keys: identity.xml property paths read via IdentityUtil.getProperty(...).
    // The Redis settings live under the first-class <RedisSessionPersistenceManager> element in
    // identity.xml (rendered from identity.xml.j2), so each property path is
    // "RedisSessionPersistenceManager.<Name>". IdentityConfigParser keys nested elements as
    // parent.child, so CONF_PREFIX MUST keep its trailing '.' — without it the keys would collapse
    // to "RedisSessionPersistenceManager<Name>" and never match. (SessionStoreImplType is separate:
    // the framework reads it from JDBCPersistenceManager.SessionDataPersist.SessionStoreImplType.)
    private static final String CONF_PREFIX = "RedisSessionPersistenceManager.";
    public static final String CONF_MODE = CONF_PREFIX + "Mode";
    public static final String CONF_HOSTS = CONF_PREFIX + "Hosts";
    public static final String CONF_MASTER_NAME = CONF_PREFIX + "MasterName";
    public static final String CONF_USERNAME = CONF_PREFIX + "Username";
    public static final String CONF_PASSWORD = CONF_PREFIX + "Password";
    public static final String CONF_DATABASE = CONF_PREFIX + "Database";
    public static final String CONF_SSL_ENABLED = CONF_PREFIX + "SSLEnabled";
    public static final String CONF_CONNECTION_TIMEOUT_MS = CONF_PREFIX + "ConnectionTimeoutMillis";
    public static final String CONF_COMMAND_TIMEOUT_MS = CONF_PREFIX + "CommandTimeoutMillis";
    public static final String CONF_KEY_PREFIX = CONF_PREFIX + "KeyPrefix";
    public static final String CONF_SERIALIZER = CONF_PREFIX + "Serializer";
    public static final String CONF_FAILURE_MODE = CONF_PREFIX + "FailureMode";

    // ---- Config defaults ----
    public static final String DEFAULT_MODE = "standalone";
    public static final String DEFAULT_HOSTS = "127.0.0.1:6379";
    public static final int DEFAULT_DATABASE = 0;
    public static final boolean DEFAULT_SSL_ENABLED = false;
    public static final long DEFAULT_CONNECTION_TIMEOUT_MS = 2000L;
    public static final long DEFAULT_COMMAND_TIMEOUT_MS = 1000L;
    public static final String DEFAULT_KEY_PREFIX = "wso2is";
    public static final String DEFAULT_SERIALIZER = "java";
    public static final String FAILURE_MODE_FAIL_CLOSED = "fail_closed";
    public static final String FAILURE_MODE_FAIL_DEGRADED = "fail_degraded";

    // ---- Topology modes ----
    public static final String MODE_STANDALONE = "standalone";
    public static final String MODE_SENTINEL = "sentinel";
    public static final String MODE_CLUSTER = "cluster";

    // ---- Key-scheme segments (design §4.2) ----
    public static final String SEG_SESSION = "session";
    public static final String SEG_AUTHCTX = "authctx";
    public static final String SUBKEY_DATA = "data";
    public static final String SUBKEY_STATE = "state";

    /**
     * The SPI {@code type} that denotes the live SSO session context; maps to the
     * {@code session:{id}:data} / {@code :state} keys. Other types map to {@code authctx}.
     */
    public static final String TYPE_SESSION_CONTEXT = "AppAuthFrameworkSessionContextCache";

    // ---- Hybrid-hash field names (design §4.3) ----
    public static final String FIELD_TENANT_ID = "tenantId";
    public static final String FIELD_TIME_CREATED = "timeCreated";
    public static final String FIELD_STATE = "state";
    public static final String FIELD_PAYLOAD_VERSION = "payloadVersion";
    public static final String FIELD_PAYLOAD = "payload";

    public static final String PAYLOAD_VERSION_1 = "1";

    // ---- Session-state values (design §5.9) ----
    public static final String STATE_ACTIVE = "ACTIVE";
    public static final String STATE_LOGGED_OUT = "LOGGED_OUT";

    /**
     * Floor applied to a computed TTL so sub-millisecond validity never rounds to 0 (which Redis
     * would reject / treat as no-expiry). Design §7.2.
     */
    public static final long MIN_TTL_MILLIS = 1000L;

    /** Short TTL for the {@code LOGGED_OUT} state marker left behind on logout. */
    public static final long LOGGED_OUT_MARKER_TTL_MILLIS = 60_000L;
}
