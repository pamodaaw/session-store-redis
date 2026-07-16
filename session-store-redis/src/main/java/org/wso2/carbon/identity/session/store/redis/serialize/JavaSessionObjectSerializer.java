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

import org.wso2.carbon.identity.application.authentication.framework.store.SessionContextDO;
import org.wso2.carbon.identity.session.store.redis.RedisSessionStoreException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;

/**
 * Default, bundle-owned {@link SessionObjectSerializer} using JDK serialization.
 *
 * <p>Serialization lives inside this bundle (the wire format is an internal detail; design §5.5),
 * defaulting to Java serialization for drop-in compatibility with the current {@code Serializable}
 * session objects. Kryo/JSON are later options.
 *
 * <p><b>Class loading.</b> The stored objects are framework cache-entry classes that live in the
 * authentication-framework bundle, not this one. So deserialization resolves classes through the
 * framework bundle's class loader (obtained from a well-known framework type,
 * {@link SessionContextDO}), falling back to this bundle's loader. This avoids
 * {@code ClassNotFoundException} across the OSGi bundle boundary.
 *
 * <p><b>Security note (SPEC §8):</b> deserializing untrusted data is a gadget-chain risk; the Redis
 * instance must be trusted infrastructure (AUTH/TLS/network isolation). A production hardening step
 * is to constrain this with an {@code ObjectInputFilter} allow-list, or prefer a schema-bound
 * serializer.
 */
public class JavaSessionObjectSerializer implements SessionObjectSerializer {

    private static final ClassLoader FRAMEWORK_CLASS_LOADER = SessionContextDO.class.getClassLoader();

    @Override
    public byte[] serialize(Object entry) {

        if (entry == null) {
            return null;
        }
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(entry);
            oos.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RedisSessionStoreException("Failed to serialize session entry.", e, true);
        }
    }

    @Override
    public Object deserialize(byte[] bytes) {

        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try (ObjectInputStream ois = new FrameworkClassLoaderObjectInputStream(
                new ByteArrayInputStream(bytes))) {
            return ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RedisSessionStoreException("Failed to deserialize session entry.", e, true);
        }
    }

    /**
     * An {@link ObjectInputStream} that resolves classes via the framework bundle's class loader
     * first, so framework session/cache-entry classes load across the bundle boundary.
     */
    private static final class FrameworkClassLoaderObjectInputStream extends ObjectInputStream {

        FrameworkClassLoaderObjectInputStream(java.io.InputStream in) throws IOException {

            super(in);
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {

            if (FRAMEWORK_CLASS_LOADER != null) {
                try {
                    return Class.forName(desc.getName(), false, FRAMEWORK_CLASS_LOADER);
                } catch (ClassNotFoundException ignored) {
                    // Fall back to the default resolution below.
                }
            }
            return super.resolveClass(desc);
        }
    }
}
