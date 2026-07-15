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

package org.wso2.carbon.identity.session.store.redis.serialize;

import org.wso2.carbon.identity.application.authentication.framework.store.SessionDataStoreUtils;
import org.wso2.carbon.identity.session.store.redis.RedisSessionStoreException;

/**
 * Default {@link SessionObjectSerializer} that delegates to the framework's registered
 * {@code SessionSerializer} via {@link SessionDataStoreUtils}.
 *
 * <p>Delegating (rather than the bundle owning its own serialization) gives two guarantees:
 * the Redis wire format is byte-identical to the JDBC blob path, and framework cache-entry classes
 * deserialize with the framework bundle's class loader (avoiding {@code ClassNotFoundException} /
 * {@code serialVersionUID} drift across bundles). The default is Java serialization; swapping it
 * is a framework-level concern, so the whole bundle inherits it for free.
 */
public class FrameworkSessionObjectSerializer implements SessionObjectSerializer {

    @Override
    public byte[] serialize(Object entry) {

        try {
            return SessionDataStoreUtils.serializeSession(entry);
        } catch (Exception e) {
            throw new RedisSessionStoreException("Failed to serialize session entry.", e, true);
        }
    }

    @Override
    public Object deserialize(byte[] bytes) {

        try {
            return SessionDataStoreUtils.deserializeSession(bytes);
        } catch (Exception e) {
            throw new RedisSessionStoreException("Failed to deserialize session entry.", e, true);
        }
    }
}
