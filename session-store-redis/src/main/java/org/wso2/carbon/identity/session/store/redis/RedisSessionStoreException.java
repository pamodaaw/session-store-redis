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
 * Unchecked failure raised by the Redis session store.
 *
 * <p>The {@code SessionDataStore} SPI declares no checked exceptions, so Redis/Lettuce failures and
 * serialization errors are surfaced as this unchecked type. {@link #isFailClosed()} carries the
 * security-relevant signal: under the default {@code fail_closed} policy a hard Redis outage must
 * be treated as "not authenticated" rather than silently degraded.
 */
public class RedisSessionStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean failClosed;

    public RedisSessionStoreException(String message, Throwable cause, boolean failClosed) {

        super(message, cause);
        this.failClosed = failClosed;
    }

    public boolean isFailClosed() {

        return failClosed;
    }
}
