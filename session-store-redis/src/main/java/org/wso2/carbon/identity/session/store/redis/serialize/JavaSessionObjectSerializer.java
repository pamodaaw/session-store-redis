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
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;

/**
 * Default, self-contained {@link SessionObjectSerializer}: standard Java serialization, drop-in
 * compatible with the framework's {@code JavaSessionSerializer} wire format (both write a bare
 * {@code ObjectOutputStream} of a {@code Serializable} entry), so bytes are interchangeable with the
 * JDBC blob path.
 *
 * <p><b>Cross-bundle class resolution.</b> The serialized payload holds framework and application
 * classes that this Redis bundle does not import. A plain {@link ObjectInputStream} resolves classes
 * against the "latest user-defined loader on the stack", which in OSGi may not see those classes. So
 * deserialization uses a resolver that tries, in order, the thread-context class loader, then the
 * framework bundle's loader (via {@link SessionContextDO}), then the default &mdash; giving access to
 * framework session classes without a compile-time dependency on them.
 */
public class JavaSessionObjectSerializer implements SessionObjectSerializer {

    @Override
    public byte[] serialize(Object entry) {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(entry);
            oos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RedisSessionStoreException("Failed to serialize session entry.", e, true);
        }
    }

    @Override
    public Object deserialize(byte[] bytes) {

        if (bytes == null) {
            return null;
        }
        try (ObjectInputStream ois =
                     new ClassLoaderAwareObjectInputStream(new ByteArrayInputStream(bytes))) {
            return ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RedisSessionStoreException("Failed to deserialize session entry.", e, true);
        }
    }

    /**
     * {@link ObjectInputStream} whose {@link #resolveClass(ObjectStreamClass)} walks a small chain of
     * class loaders so payloads written by other bundles (framework/application session classes)
     * resolve regardless of which loader happens to be on the stack.
     */
    private static final class ClassLoaderAwareObjectInputStream extends ObjectInputStream {

        private ClassLoaderAwareObjectInputStream(InputStream in) throws IOException {

            super(in);
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass desc)
                throws IOException, ClassNotFoundException {

            String name = desc.getName();
            ClassLoader tccl = Thread.currentThread().getContextClassLoader();
            if (tccl != null) {
                try {
                    return Class.forName(name, false, tccl);
                } catch (ClassNotFoundException ignored) {
                    // Fall through to the framework loader.
                }
            }
            ClassLoader frameworkLoader = SessionContextDO.class.getClassLoader();
            if (frameworkLoader != null) {
                try {
                    return Class.forName(name, false, frameworkLoader);
                } catch (ClassNotFoundException ignored) {
                    // Fall through to the default resolution.
                }
            }
            return super.resolveClass(desc);
        }
    }
}
