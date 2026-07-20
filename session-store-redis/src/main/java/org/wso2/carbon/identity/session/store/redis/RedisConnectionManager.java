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

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.session.store.redis.config.RedisStoreConfig;

import java.time.Duration;

/**
 * Owns the Lettuce client and a single shared connection (design §5.6). Lettuce is Netty-based and
 * thread-safe, so one {@link StatefulRedisConnection} serves all auth threads &mdash; no
 * borrow-per-operation pool is needed for normal commands. The connection is encapsulated here;
 * the SPI never exposes it.
 *
 * <p><b>Codec:</b> keys and hash-field names are UTF-8 strings, values (including the serialized
 * payload) are raw bytes &mdash; the composition that makes the hybrid hash (string metadata +
 * binary payload) work in one structure.
 *
 * <p><b>Topology (MVP):</b> only {@code standalone} is implemented. {@code sentinel}/{@code cluster}
 * throw {@link UnsupportedOperationException} and are a later iteration (design §5.6, §8.1).
 */
class RedisConnectionManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RedisConnectionManager.class);

    private final RedisClient client;
    private final StatefulRedisConnection<String, byte[]> connection;

    RedisConnectionManager(RedisStoreConfig config) {

        if (!RedisConstants.MODE_STANDALONE.equalsIgnoreCase(config.getMode())) {
            // Sentinel/Cluster need RedisClusterClient / Sentinel URIs and hash-tagged keys
            // (already produced by RedisKeyBuilder). Deferred to a later iteration.
            throw new UnsupportedOperationException(
                    "Redis topology '" + config.getMode() + "' is not supported in this MVP; "
                            + "only 'standalone' is available.");
        }

        RedisURI uri = buildStandaloneUri(config);
        // Composed codec: String keys/fields, byte[] values -> hybrid hash support.
        RedisCodec<String, byte[]> codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);

        // Pin the thread-context class loader to this bundle while Lettuce/Netty initialise.
        // On first client creation Lettuce probes for optional libraries (notably netty's
        // non-blocking DNS resolver) via the TCCL. In OSGi the activation thread's TCCL may belong
        // to another bundle that exposes a *different* copy of Netty; loading part of Netty from
        // there and part from this bundle's embedded copy splits Netty's class space and fails JVM
        // verification (DnsAddressResolverGroup vs AddressResolverGroup). Pinning the TCCL keeps all
        // resolution inside this bundle: the DNS resolver is (correctly) seen as absent and Lettuce
        // falls back to the default resolver bundled here.
        ClassLoader previousTccl = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(RedisConnectionManager.class.getClassLoader());
            this.client = RedisClient.create(uri);
            // Fail fast on connect and per command rather than blocking the auth thread.
            this.client.setOptions(ClientOptions.builder()
                    .autoReconnect(true) // ride out transient blips (design §9.2)
                    .socketOptions(SocketOptions.builder()
                            .connectTimeout(Duration.ofMillis(config.getConnectionTimeoutMs()))
                            .build())
                    .build());
            this.connection = client.connect(codec);
            this.connection.setTimeout(Duration.ofMillis(config.getCommandTimeoutMs()));
            LOG.info("Connected Redis session store to {}:{} (db {}, ssl {}).",
                    uri.getHost(), uri.getPort(), config.getDatabase(), config.isSslEnabled());
        } catch (RuntimeException e) {
            throw new RedisSessionStoreException(
                    "Failed to connect to Redis at " + config.getHosts(), e, config.isFailClosed());
        } finally {
            Thread.currentThread().setContextClassLoader(previousTccl);
        }
    }

    private static RedisURI buildStandaloneUri(RedisStoreConfig config) {

        // Standalone uses the first host:port entry.
        String first = config.getHosts().split(",")[0].trim();
        int colon = first.lastIndexOf(':');
        String host = colon > 0 ? first.substring(0, colon) : first;
        int port = colon > 0 ? Integer.parseInt(first.substring(colon + 1)) : 6379;

        RedisURI.Builder builder = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withSsl(config.isSslEnabled())
                .withDatabase(config.getDatabase())
                .withTimeout(Duration.ofMillis(config.getCommandTimeoutMs()));

        if (config.getPassword() != null && !config.getPassword().isBlank()) {
            if (config.getUsername() != null && !config.getUsername().isBlank()) {
                builder.withAuthentication(config.getUsername(), config.getPassword().toCharArray());
            } else {
                builder.withPassword(config.getPassword().toCharArray());
            }
        }
        return builder.build();
    }

    /**
     * @return the shared synchronous command interface. Safe to call concurrently.
     */
    RedisCommands<String, byte[]> sync() {

        return connection.sync();
    }

    @Override
    public void close() {

        try {
            if (connection != null) {
                connection.close();
            }
        } finally {
            if (client != null) {
                client.shutdown();
            }
        }
    }
}
