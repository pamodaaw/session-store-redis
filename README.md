# Redis Session Store (self-contained incubator bundle)

Realizes the configurable Redis-backed session storage from [`../SPEC.md`](../SPEC.md) and
[`../design.md`](../design.md). `design.md` is the authority where it and the SPEC disagree.

## Where the pieces live

The pluggable **SPI** and the **JDBC default** ship inside `carbon-identity-framework`; only the
**Redis backend** is this self-contained, separately-deployable OSGi bundle.

**In `carbon-identity-framework`** (module `org.wso2.carbon.identity.application.authentication.framework`,
package `...framework.store`) — all additive, backward-compatible:
- `SessionStore` — the SPI. Method signatures mirror the existing `SessionDataStore` call sites.
- `JDBCSessionDataStore` — default backend (`getStoreName()="JDBC"`); a thin adapter that
  delegates to the unchanged `SessionDataStore` singleton. `isSessionLive` = read-only
  `getSessionContextData(...) != null`.
- `SessionDataStoreProvider` — resolves the active store from
  `JDBCPersistenceManager.SessionDataPersist.SessionStoreImplType` (default `JDBC`); caches it;
  `WARN` + JDBC fallback when a configured store is absent; `invalidate()` on bind/unbind.
- `SessionDataStoreUtils` — additive helper exposing TTL derivation
  (`getValidityPeriodNano`) and serialize/deserialize (delegating to the framework's registered
  `SessionSerializer`) so external stores match JDBC exactly without touching internals.
- `FrameworkServiceDataHolder` — a `Map<String,SessionStore>` registry + accessors.
- `FrameworkServiceComponent` — a `MULTIPLE`/`DYNAMIC` `@Reference(SessionStore)` that collects
  external stores, and self-registers the JDBC default in `@Activate`.
- `SessionContextCache` + `AuthenticationContextCache` — the only behavior edit: their store
  accessor swaps `SessionDataStore.getInstance()` → `SessionDataStoreProvider.getStore()`
  (arguments unchanged; identical behavior until a Redis store binds).

The existing `SessionDataStore`, `SessionContextDO`, the append-only schema, and the cleanup
services are **untouched**. The core has **zero** Redis/Lettuce dependency.

**This bundle** (`session-store-redis`):
- `RedisSessionDataStore implements SessionStore` (`getStoreName()="Redis"`,
  `supportsNativeExpiry()=true`): hybrid-hash layout, native per-key TTL written atomically
  (Lua `HSET`+`PEXPIRE`), `:state` logout marker, tenant-pointer key, `EXISTS` liveness, `DEL`/
  `UNLINK` clears, `removeExpiredSessionData()` no-op. TTL + (de)serialization go through
  `SessionDataStoreUtils` for parity with JDBC.
- `RedisConnectionManager` (Lettuce, standalone), `RedisKeyBuilder` (canonical tenant-inclusive,
  hash-tagged keys), `RedisConstants`, `RedisStoreConfig` (reads `identity.xml` via
  `IdentityUtil`), `serialize/*`, `internal/RedisSessionStoreComponent` (DS `@Component` that
  registers the store as a `SessionStore` OSGi service after `IdentityCoreInitializedEvent`).
- Depends on the framework artifact (`provided`); embeds Lettuce + Netty so nothing leaks onto
  the core classpath.

## How a backend is selected

`SessionDataStoreProvider.getStore()` reads the configured store name (default `JDBC`). Drop this
bundle in and set `...SessionStoreImplType = Redis` → the DS component registers the Redis store,
the framework reference binds it, the provider resolves it. Remove the bundle or revert the
config → provider falls back to JDBC, no code change.

## Build & test

**Framework side** (from the framework repo root):
```bash
mvn -pl components/authentication-framework/org.wso2.carbon.identity.application.authentication.framework -am clean install
```
Compiles the SPI/adapter/provider/utils + wiring, passes checkstyle. `install` publishes the
artifact this bundle compiles against.

**This bundle** (from this dir):
```bash
mvn clean test       # unit tests always run; Redis tests use Testcontainers
mvn clean package    # produces the OSGi bundle with Lettuce + Netty embedded
```
**Unit tests (always run, no Docker):**
- `RedisKeyBuilderTest` — canonical key scheme (tenant in every key, `:data`/`:state`, hash tag).
- `RedisStoreConfigTest` — config defaults, builder overrides, fail-closed vs fail-degraded decision.
- `RedisSessionDataStoreConstructionTest` — Sentinel/Cluster reject fast; an unreachable Redis
  surfaces the fail-closed signal (fail-closed by default, fail-degraded only when opted in).

**Integration tests (Testcontainers Redis, skipped-not-failed without Docker via
`@Testcontainers(disabledWithoutDocker = true)`):**
- `RedisSessionStoreIntegrationTest` — round-trip; TTL-always-set on every key; hybrid-hash fields;
  tenant-scoped metadata; TTL slide on re-store; sub-second TTL floor; already-expired no-op;
  native expiry→miss; logout marker + bounded TTL; hard remove (no marker); batch clear; temporary
  authctx round-trip/remove; absent-read returns null.

For the end-to-end / server-level manual scenarios (login, SSO, logout, remember-me, MFA,
fallback, TLS/AUTH, performance) see [`../docs/manual-test-plan.md`](../docs/manual-test-plan.md).

## Manual verification against a real Redis

```bash
docker compose -f ../docs/docker-compose.yml up -d
```
Then point an IS deployment (or a small harness) at it with
`...SessionStoreImplType = Redis` + the `...Redis.*` keys, log in, and confirm via
[`../docs/redis-dev-deployment.md`](../docs/redis-dev-deployment.md) §5: keys appear under
`wso2is:<tenant>:session:...`, every key has `TTL ≥ 0`, and logout leaves a short-lived
`:state = LOGGED_OUT`.

## Notable design choices

- **TTL/serialization parity** with JDBC is achieved by routing both through the new
  `SessionDataStoreUtils` in the framework — the Redis wire format and lifetimes match the DB
  path, and framework cache-entry classes deserialize with the framework's class loader.
- **Tenant-pointer key** (`{prefix}:ref:{id}` → tenantId): the SPI reads take only `(key,type)`
  while the key scheme embeds `tenantId`; a small non-sensitive pointer bridges this. A later
  iteration can thread the tenant through the cache→store read path and drop it.

## Deferred to later iterations

Management-API liveness wiring (`UserSessionStore.getSessionsTerminated` → `isSessionLive`) +
orphan reconciliation; Sentinel/Cluster topologies; sorted-set indexes (Option B); metrics/health;
Kryo/JSON serializers; the `identity.xml.j2`/`*.default.json`/deployment.toml config plumbing;
routing the other four caches.
