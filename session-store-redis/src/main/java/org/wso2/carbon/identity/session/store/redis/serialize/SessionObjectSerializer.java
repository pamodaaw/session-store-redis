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

/**
 * Pluggable serializer SPI internal to the Redis bundle (design §5.5). The wire format is an
 * implementation detail of this bundle; the default is Java serialization (drop-in compatible
 * with the current {@code Serializable} session objects), with Kryo/JSON as later options.
 *
 * <p>The serialized bytes are stored as the opaque {@code payload} field of the hybrid hash; the
 * {@code payloadVersion} metadata field (design §4.3) supports version-aware deserialization
 * across upgrades.
 */
public interface SessionObjectSerializer {

    byte[] serialize(Object entry);

    Object deserialize(byte[] bytes);
}
