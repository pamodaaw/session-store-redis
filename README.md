# Redis Session Store (self-contained incubator bundle)

Realizes the configurable Redis-backed session storage from [`../SPEC.md`](../SPEC.md) and
[`../design.md`](../design.md). `design.md` is the authority where it and the SPEC disagree.

## Where the pieces live

The pluggable **SPI** and the **JDBC default** ship inside `carbon-identity-framework`; only the
**Redis backend** is this self-contained, separately-deployable OSGi bundle.

**In `carbon-identity-framework`** (module `org.wso2.carbon.identity.application.authentication.framework`,
package `...framework.store`) — the pluggability contract, backward-compatible for callers:
- `SessionDataStore` — now the **abstract supertype** every caller uses. `getInstance()` and all
  public method signatures are unchanged, so call sites keep working. It selects the active store
  from `JDBCPersistenceManager.SessionDataPersist.SessionStoreImplType` (default `JDBC`), caches it,
  and `invalidateSelectedStore()` on bind/unbind. Selection is **fail-closed**: a configured
  non-JDBC store that is not yet registered throws rather than falling back to JDBC (which would
  split session data across stores). It also exposes the `protected getValidityPeriodNano(...)`
  helper so native-TTL stores size their expiry exactly as JDBC computes `EXPIRY_TIME`.
- `JDBCSessionDataStore extends SessionDataStore` — the default relational backend
  (`getStoreName()="JDBC"`); the existing async queues, schema and cleanup services moved here
  verbatim.
- `FrameworkServiceDataHolder` — a `Map<String,SessionDataStore>` registry (keyed by
  case-insensitive `getStoreName()`) + accessors.
- `FrameworkServiceComponent` — a `MULTIPLE`/`DYNAMIC` `@Reference(SessionDataStore.class)`
  (`session.data.store`) that collects external stores and invalidates the cached selection on
  bind/unbind.

The `SessionContextDO`, the append-only schema, and the JDBC cleanup services are **untouched**.
The core has **zero** Redis/Lettuce dependency and no compile-time reference to any external store.

**This bundle** (`session-store-redis`):
- `RedisSessionDataStore extends SessionDataStore` (`getStoreName()="Redis"`): hybrid-hash layout,
  native per-key TTL written atomically (Lua `HSET`+`PEXPIRE`), `:state` logout marker,
  tenant-pointer key, `EXISTS` liveness, `DEL`/`UNLINK` clears, `removeExpiredSessionData()` no-op,
  `stopService()` releases the Lettuce client. TTL derivation reuses the inherited
  `getValidityPeriodNano(...)` for parity with JDBC.
- `serialize/JavaSessionObjectSerializer` — self-contained Java serialization, wire-compatible with
  the framework's `JavaSessionSerializer` blob format, with a class-loader-aware `ObjectInputStream`
  so framework/application session classes resolve across bundles.
- `RedisConnectionManager` (Lettuce, standalone), `RedisKeyBuilder` (canonical tenant-inclusive,
  hash-tagged keys), `RedisConstants`, `RedisStoreConfig` (reads `identity.xml` via
  `IdentityUtil`), `internal/RedisSessionStoreComponent` (DS `@Component` that registers the store
  as a `SessionDataStore` OSGi service after `IdentityCoreInitializedEvent`).
- Depends on the framework artifact (`provided`); embeds Lettuce + Netty so nothing leaks onto
  the core classpath.

## How a backend is selected

`SessionDataStore.getInstance()` reads the configured store name (default `JDBC`). Drop this
bundle in and set `...SessionStoreImplType = Redis` → the DS component registers the Redis store
under `SessionDataStore.class`, the framework reference binds it and invalidates the cached
selection, and the next `getInstance()` resolves Redis. While a non-JDBC store is configured but not
yet bound, resolution fails closed (it does not silently fall back to JDBC).

## Configuring the Redis backend

Redis settings live under a first-class `<RedisSessionPersistenceManager>` element in `identity.xml`
(rendered from `identity.xml.j2`), i.e. property keys `RedisSessionPersistenceManager.<Name>`, read
via `IdentityUtil.getProperty`. Any unset key falls back to its built-in default.

Common keys: `RedisSessionPersistenceManager.Hosts`, `.Username`, `.Password`, `.Database`,
`.SSLEnabled`, `.KeyPrefix`, `.FailureMode`. The store selection itself is separate and read by the
framework from `JDBCPersistenceManager.SessionDataPersist.SessionStoreImplType` (set it to `Redis`).

If your Redis has `requirepass` set and `RedisSessionPersistenceManager.Password` is unset, the
client connects unauthenticated and Redis rejects the RESP3 `HELLO` with `NOAUTH ...` — set the
password in `identity.xml.j2`.

## Build & test

**Framework side** (from the framework repo root):
```bash
mvn -pl components/authentication-framework/org.wso2.carbon.identity.application.authentication.framework -am clean install
```
Compiles the abstract `SessionDataStore` + `JDBCSessionDataStore` + OSGi wiring, passes checkstyle.
`install` publishes the artifact this bundle compiles against (framework `7.11.161-SNAPSHOT`).

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

- **TTL parity** with JDBC is achieved by reusing the inherited `getValidityPeriodNano(...)` from
  the abstract `SessionDataStore`, so Redis key lifetimes match the DB `EXPIRY_TIME`.
- **Serialization parity** with JDBC: `JavaSessionObjectSerializer` writes the same bare
  `ObjectOutputStream` blob format as the framework's `JavaSessionSerializer`, and a
  class-loader-aware `ObjectInputStream` resolves framework/application session classes across
  bundles (tries the thread-context loader, then the framework bundle's loader).
- **Composite `(key, type)` identity**: the framework's logical primary key is `(key, type)`, so
  the `authctx` data keys and the `ref` pointer both include `type`
  (`{prefix}:{tenantId}:authctx:{type}:{contextId}`, `{prefix}:ref:{type}:{id}`). Without it, caches
  that share a `key` value under different types (e.g. `SessionDataCache` and
  `AuthenticationResultCache`) collided last-write-wins and a read returned the wrong type →
  `ClassCastException`. The session-context hybrid-hash keyspace is unchanged (only one type uses it).
- **Tenant-pointer key** (`{prefix}:ref:{type}:{id}` → tenantId): the SPI reads take only
  `(key, type)` while the data keys embed `tenantId`; a small non-sensitive, `(type,id)`-scoped
  pointer bridges this. A later iteration can thread the tenant through the cache→store read path
  and drop it.

## Deferred to later iterations

Management-API liveness wiring (`UserSessionStore.getSessionsTerminated` → `isSessionLive`) +
orphan reconciliation; Sentinel/Cluster topologies; sorted-set indexes (Option B); metrics/health;
Kryo/JSON serializers; the `identity.xml.j2`/`*.default.json`/deployment.toml config plumbing;
routing the other four caches.
