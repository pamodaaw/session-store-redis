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

import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.application.authentication.framework.store.SessionStore;
import org.wso2.carbon.identity.core.util.IdentityCoreInitializedEvent;
import org.wso2.carbon.identity.session.store.redis.RedisSessionDataStore;

/**
 * OSGi activator for the Redis session-store bundle.
 *
 * <p>On activation it constructs a {@link RedisSessionDataStore} and registers it as a
 * {@link SessionStore} OSGi service. The framework's {@code session.store} reference
 * ({@code MULTIPLE}/{@code DYNAMIC}) collects it, so {@code SessionDataStoreProvider} can resolve
 * {@code getStoreName() == "Redis"}. Dropping this bundle in and setting the store type to
 * {@code Redis} activates Redis; removing the bundle deregisters the store and the provider falls
 * back to JDBC &mdash; no code change either way.
 *
 * <p>The {@code @Reference} on {@link IdentityCoreInitializedEvent} orders activation after the
 * identity core has started.
 */
@Component(name = "org.wso2.carbon.identity.session.store.redis", immediate = true)
public class RedisSessionStoreComponent {

    private static final Logger LOG = LoggerFactory.getLogger(RedisSessionStoreComponent.class);

    private ServiceRegistration<?> serviceRegistration;

    @Activate
    protected void activate(ComponentContext context) {

        try {
            RedisSessionDataStore store = new RedisSessionDataStore();
            RedisSessionStoreDataHolder.getInstance().setSessionDataStore(store);
            serviceRegistration = context.getBundleContext()
                    .registerService(SessionStore.class.getName(), store, null);
            LOG.info("Redis session store registered as SessionStore '{}'.", store.getStoreName());
        } catch (RuntimeException e) {
            // Do not abort the whole runtime if Redis is misconfigured/unreachable; the framework
            // provider will fall back to JDBC.
            LOG.error("Failed to activate Redis session store; the framework will fall back to the "
                    + "default backend.", e);
        }
    }

    @Deactivate
    protected void deactivate(ComponentContext context) {

        if (serviceRegistration != null) {
            serviceRegistration.unregister();
            serviceRegistration = null;
        }
        RedisSessionDataStore store = RedisSessionStoreDataHolder.getInstance().getSessionDataStore();
        if (store != null) {
            store.close();
            RedisSessionStoreDataHolder.getInstance().setSessionDataStore(null);
        }
        LOG.info("Redis session store deregistered.");
    }

    @Reference(
            name = "identity.core.init.event.service",
            service = IdentityCoreInitializedEvent.class,
            cardinality = ReferenceCardinality.MANDATORY,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetIdentityCoreInitializedEventService"
    )
    protected void setIdentityCoreInitializedEventService(IdentityCoreInitializedEvent event) {

        /* Reference to ensure this component activates only after identity core is initialized. */
    }

    protected void unsetIdentityCoreInitializedEventService(IdentityCoreInitializedEvent event) {

        /* Reference to ensure this component activates only after identity core is initialized. */
    }
}
