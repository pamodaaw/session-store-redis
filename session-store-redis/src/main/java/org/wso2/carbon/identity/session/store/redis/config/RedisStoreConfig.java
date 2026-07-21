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

import org.apache.commons.lang.StringUtils;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.session.store.redis.RedisConstants;

/**
 * Typed, immutable snapshot of the Redis backend configuration, read from {@code identity.xml} via
 * {@link IdentityUtil#getProperty(String)} (the same mechanism the rest of the framework uses).
 * Keys live under the first-class {@code <RedisSessionPersistenceManager>} element, i.e.
 * {@code RedisSessionPersistenceManager.<Name>}. Applies the documented defaults for any unset key.
 */
public final class RedisStoreConfig {

    private final String mode;
    private final String hosts;
    private final String masterName;
    private final String username;
    private final String password;
    private final int database;
    private final boolean sslEnabled;
    private final long connectionTimeoutMs;
    private final long commandTimeoutMs;
    private final String keyPrefix;
    private final String serializer;
    private final String failureMode;

    private RedisStoreConfig(Builder b) {

        this.mode = b.mode;
        this.hosts = b.hosts;
        this.masterName = b.masterName;
        this.username = b.username;
        this.password = b.password;
        this.database = b.database;
        this.sslEnabled = b.sslEnabled;
        this.connectionTimeoutMs = b.connectionTimeoutMs;
        this.commandTimeoutMs = b.commandTimeoutMs;
        this.keyPrefix = b.keyPrefix;
        this.serializer = b.serializer;
        this.failureMode = b.failureMode;
    }

    /**
     * Reads the current configuration from {@code identity.xml} via {@link IdentityUtil}, applying
     * defaults for any unset key.
     */
    public static RedisStoreConfig fromConfig() {

        Builder b = new Builder();
        b.mode = get(RedisConstants.CONF_MODE, RedisConstants.DEFAULT_MODE);
        b.hosts = get(RedisConstants.CONF_HOSTS, RedisConstants.DEFAULT_HOSTS);
        b.masterName = get(RedisConstants.CONF_MASTER_NAME, "");
        b.username = get(RedisConstants.CONF_USERNAME, "");
        b.password = get(RedisConstants.CONF_PASSWORD, "");
        b.database = parseInt(IdentityUtil.getProperty(RedisConstants.CONF_DATABASE),
                RedisConstants.DEFAULT_DATABASE);
        b.sslEnabled = Boolean.parseBoolean(get(RedisConstants.CONF_SSL_ENABLED,
                String.valueOf(RedisConstants.DEFAULT_SSL_ENABLED)));
        b.connectionTimeoutMs = parseLong(IdentityUtil.getProperty(RedisConstants.CONF_CONNECTION_TIMEOUT_MS),
                RedisConstants.DEFAULT_CONNECTION_TIMEOUT_MS);
        b.commandTimeoutMs = parseLong(IdentityUtil.getProperty(RedisConstants.CONF_COMMAND_TIMEOUT_MS),
                RedisConstants.DEFAULT_COMMAND_TIMEOUT_MS);
        b.keyPrefix = get(RedisConstants.CONF_KEY_PREFIX, RedisConstants.DEFAULT_KEY_PREFIX);
        b.serializer = get(RedisConstants.CONF_SERIALIZER, RedisConstants.DEFAULT_SERIALIZER);
        b.failureMode = get(RedisConstants.CONF_FAILURE_MODE, RedisConstants.FAILURE_MODE_FAIL_CLOSED);
        return new RedisStoreConfig(b);
    }

    private static String get(String key, String defaultValue) {

        String value = IdentityUtil.getProperty(key);
        return StringUtils.isBlank(value) ? defaultValue : value;
    }

    private static int parseInt(String value, int fallback) {

        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {

        try {
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public String getMode() {

        return mode;
    }

    public String getHosts() {

        return hosts;
    }

    public String getMasterName() {

        return masterName;
    }

    public String getUsername() {

        return username;
    }

    public String getPassword() {

        return password;
    }

    public int getDatabase() {

        return database;
    }

    public boolean isSslEnabled() {

        return sslEnabled;
    }

    public long getConnectionTimeoutMs() {

        return connectionTimeoutMs;
    }

    public long getCommandTimeoutMs() {

        return commandTimeoutMs;
    }

    public String getKeyPrefix() {

        return keyPrefix;
    }

    public String getSerializer() {

        return serializer;
    }

    public String getFailureMode() {

        return failureMode;
    }

    public boolean isFailClosed() {

        return !RedisConstants.FAILURE_MODE_FAIL_DEGRADED.equalsIgnoreCase(failureMode);
    }

    /** Mutable builder, primarily for tests that need a hand-rolled config. */
    public static final class Builder {

        private String mode = RedisConstants.DEFAULT_MODE;
        private String hosts = RedisConstants.DEFAULT_HOSTS;
        private String masterName = "";
        private String username = "";
        private String password = "";
        private int database = RedisConstants.DEFAULT_DATABASE;
        private boolean sslEnabled = RedisConstants.DEFAULT_SSL_ENABLED;
        private long connectionTimeoutMs = RedisConstants.DEFAULT_CONNECTION_TIMEOUT_MS;
        private long commandTimeoutMs = RedisConstants.DEFAULT_COMMAND_TIMEOUT_MS;
        private String keyPrefix = RedisConstants.DEFAULT_KEY_PREFIX;
        private String serializer = RedisConstants.DEFAULT_SERIALIZER;
        private String failureMode = RedisConstants.FAILURE_MODE_FAIL_CLOSED;

        public Builder mode(String mode) {

            this.mode = mode;
            return this;
        }

        public Builder hosts(String hosts) {

            this.hosts = hosts;
            return this;
        }

        public Builder failureMode(String failureMode) {

            this.failureMode = failureMode;
            return this;
        }

        public Builder password(String password) {

            this.password = password;
            return this;
        }

        public Builder database(int database) {

            this.database = database;
            return this;
        }

        public Builder keyPrefix(String keyPrefix) {

            this.keyPrefix = keyPrefix;
            return this;
        }

        public Builder commandTimeoutMs(long commandTimeoutMs) {

            this.commandTimeoutMs = commandTimeoutMs;
            return this;
        }

        public RedisStoreConfig build() {

            return new RedisStoreConfig(this);
        }
    }
}
