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

package org.wso2.carbon.identity.session.store.redis.internal;

import org.wso2.carbon.identity.application.authentication.framework.store.JavaSessionSerializer;
import org.wso2.carbon.identity.application.authentication.framework.store.SessionSerializer;

/**
 * Holds the session serializer of the Redis session store bundle.
 */
public class RedisSessionStoreDataHolder {

    private static final RedisSessionStoreDataHolder INSTANCE = new RedisSessionStoreDataHolder();

    private volatile SessionSerializer sessionSerializer = new JavaSessionSerializer();

    private RedisSessionStoreDataHolder() {

    }

    /**
     * The instance of the data holder.
     *
     * @return the data holder.
     */
    public static RedisSessionStoreDataHolder getInstance() {

        return INSTANCE;
    }

    /**
     * The serializer every session object is written and read with.
     *
     * @return the serializer in use.
     */
    public SessionSerializer getSessionSerializer() {

        return sessionSerializer;
    }

    /**
     * Sets the serializer every session object is written and read with.
     *
     * @param sessionSerializer Serializer to use. Never null; the component restores Java
     *                          serialization rather than clearing it.
     */
    public void setSessionSerializer(SessionSerializer sessionSerializer) {

        this.sessionSerializer = sessionSerializer;
    }
}
