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

import org.wso2.carbon.identity.session.store.redis.RedisSessionDataStore;

/**
 * Holds the bundle's singletons (the live {@link RedisSessionDataStore}) for the activator and any
 * collaborators. Mirrors the Carbon {@code *DataHolder} convention (SPEC §7.3).
 */
public class RedisSessionStoreDataHolder {

    private static final RedisSessionStoreDataHolder INSTANCE = new RedisSessionStoreDataHolder();

    private RedisSessionDataStore sessionDataStore;

    private RedisSessionStoreDataHolder() {

    }

    public static RedisSessionStoreDataHolder getInstance() {

        return INSTANCE;
    }

    public RedisSessionDataStore getSessionDataStore() {

        return sessionDataStore;
    }

    public void setSessionDataStore(RedisSessionDataStore sessionDataStore) {

        this.sessionDataStore = sessionDataStore;
    }
}
