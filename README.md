# session-store-redis

Redis-backed session data store for WSO2 Identity Server — a separately-deployable OSGi bundle
that **extends** the `SessionDataStore` supertype shipped in
[`carbon-identity-framework`](https://github.com/wso2/carbon-identity-framework). It is an
**opt-in** replacement for the relational session-data store on the authentication hot path,
using Redis' native per-key TTL instead of app-side cleanup.

The framework keeps JDBC as the default and has **zero** dependency on this bundle. Dropping the
bundle in and pointing one config key at it activates Redis; removing it falls back to JDBC with
no code change.

## Where the pieces live

In `carbon-identity-framework` (package `...framework.store`), `SessionDataStore` is the abstract
supertype every caller uses, and `getInstance()` selects the active store; the relational
`JDBCSessionDataStore` and this bundle's `RedisSessionDataStore` are **peer** implementations at
the same pluggable level. Callers (`SessionDataStore.getInstance()`) are unchanged.

## Layout

```
session-store-redis/                                     (this OSGi bundle)
└── src/main/java/org/wso2/carbon/identity/session/store/redis/
    ├── RedisSessionDataStore.java                extends SessionDataStore
    ├── RedisConnectionManager.java               Lettuce client + connection lifecycle
    ├── RedisKeyBuilder.java                      canonical tenant-inclusive keyspace
    ├── RedisConstants.java                       config keys, key segments, TTL constants
    ├── RedisSessionStoreException.java           unchecked failure (fail-closed aware)
    ├── config/RedisStoreConfig.java              reads identity.xml via IdentityUtil
    ├── serialize/SessionObjectSerializer.java    serializer seam
    ├── serialize/JavaSessionObjectSerializer.java  bundle-owned Java serializer
    └── internal/                                 OSGi activator + data holder
```

## How it works

- **Registration.** `RedisSessionStoreComponent` (`@Component`, ordered after
  `IdentityCoreInitializedEvent`) registers `RedisSessionDataStore` as a `SessionDataStore` OSGi
  service. The framework's `session.data.store` reference collects it, and
  `SessionDataStore.getInstance()` returns it when the configured store type is `Redis`.
- **Native TTL.** Every key is written atomically with its TTL (single `SET ... PX`, or Lua
  `HSET`+`PEXPIRE` for the hybrid hash) — no key without an expiry. `removeExpiredSessionData()`
  is a no-op; Redis evicts expired keys itself.
- **Parity with JDBC.** `RedisSessionDataStore` derives each record's validity via the inherited
  `SessionDataStore.getValidityPeriodNano(...)` — the same logic the relational store uses to
  compute `EXPIRY_TIME` — so lifetimes match. Serialization is owned by this bundle (Java
  serialization by default); its `ObjectInputStream` resolves classes through the framework
  bundle's class loader so framework cache-entry classes load across the OSGi boundary.
- **Keyspace.** `{prefix}:{tenantId}:session:{<sessionId>}:data` (hybrid hash) + `:state`
  (`ACTIVE`/`LOGGED_OUT`), `{prefix}:{tenantId}:authctx:{contextId}` for auth-flow/temp data, and a
  small `{prefix}:ref:{id}` tenant-pointer (the reads carry no tenant). Session ids are hash-tagged
  for Redis Cluster.

## Configuration

Under `JDBCPersistenceManager.SessionDataPersist` in `identity.xml`/`deployment.toml`:

| Property | Default | Meaning |
|---|---|---|
| `SessionStoreImplType` | `JDBC` | Set to `Redis` to activate this bundle (read by `SessionDataStore.getInstance()`). |
| `Redis.Mode` | `standalone` | `standalone` (MVP). `sentinel`/`cluster` are a later iteration. |
| `Redis.Hosts` | `127.0.0.1:6379` | Comma-separated `host:port`. |
| `Redis.Username` / `Redis.Password` | – | Redis 6+ ACL user / AUTH password (use secure-vault). |
| `Redis.Database` | `0` | Logical DB (standalone). |
| `Redis.SSLEnabled` | `false` | Enable TLS. |
| `Redis.ConnectionTimeoutMillis` / `Redis.CommandTimeoutMillis` | `2000` / `1000` | Timeouts. |
| `Redis.KeyPrefix` | `wso2is` | Keyspace prefix. |
| `Redis.FailureMode` | `fail_closed` | `fail_closed` \| `fail_degraded`. |

## Build & test

```bash
mvn clean install
```

Requires the matching `carbon-identity-framework` artifact (see `carbon.identity.framework.version`
in the parent pom) in the local Maven repo.

**Unit tests (always run, no Docker):** `RedisKeyBuilderTest` (canonical key scheme),
`RedisStoreConfigTest` (config defaults/overrides, fail-closed vs fail-degraded),
`RedisSessionDataStoreConstructionTest` (Sentinel/Cluster reject fast; unreachable Redis surfaces
the fail-closed signal).

**Integration tests (Testcontainers Redis, skipped-not-failed without Docker via
`@Testcontainers(disabledWithoutDocker = true)`):** `RedisSessionStoreIntegrationTest` — round-trip;
TTL-always-set on every key; hybrid-hash metadata; tenant-scoped keys; TTL slide on re-store;
sub-second TTL floor; native expiry→miss; logout marker; hard remove; temporary authctx
round-trip/remove; absent-read returns null.

## Deferred / roadmap

Sentinel & Cluster topologies; sorted-set indexes for session-management list/terminate;
management-API liveness wiring; metrics & health checks; Kryo/JSON serializers; the
`identity.xml.j2`/`*.default.json` config plumbing.
