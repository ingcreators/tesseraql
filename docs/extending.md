# Extending the framework

Most applications need no Java at all: routes, SQL, views, workflows, and shared definitions
cover the ordinary cases. When they do not, there is a ladder — four rungs, each with a
different reach and a different cost. Take the lowest one that solves the problem.

| Rung | Add | Reach | Cost |
| --- | --- | --- | --- |
| 0 | A declaration | Everything documented | None |
| 1 | An expression function | One predicate, anywhere expressions run | Not admissible for distribution |
| 2 | A service provider binding | Runtime state a route can read | Route becomes `extended`; not admissible |
| 3 | A plugin jar | Camel routes and beans at boot | Signed jar; not admissible |

"Not admissible" means `tesseraql admission` fails, so the application cannot be distributed
for other people to install ([admission.md](admission.md)). It says nothing about running it
yourself, which is the normal case — see [governance.md](governance.md) for what each rung
costs on an everyday build.

## Rung 0: check the declaration first

Before writing Java, check that the declaration does not already exist. The
[YAML surface reference](reference-yaml-surface.md) is generated from the schema, so it is
complete by construction. Several things people reach for Java to do are declarative:
conditional statuses, nested composition, computed fields
([response-shaping.md](response-shaping.md)), business rules that change without a release
(decision tables), and multi-statement operations in one transaction
([transactional-writes.md](transactional-writes.md)).

## Rung 1: a custom expression function

When a validation rule needs one predicate the built-ins cannot express — a checksum, a
code-format rule, a calendar check — implement `ExpressionFunction` and ship it as a
**module**, not a plugin.

The full recipe, with the purity contract and the failure modes, is in
[declarative-validation.md](declarative-validation.md#custom-functions). In short: one class
per function, registered in `META-INF/services`, declared under `tesseraql.modules`, and
pinned by `modules.lock`.

Modules are the reviewed, lock-pinned channel, which is exactly why expression functions load
from there and never from `plugins/`.

Module visibility equals runtime scope: in a stack, each application's runtime loads its own
`work/modules` on its own classloader, so your function is visible exactly to the application
that declared it — a neighbour declaring a same-named function keeps its own semantics, and
neither can shadow the other. Changing the declared set is a restart (`dev` re-resolves on
start; a hosted member is redeployed), never a live swap.

## Rung 2: a service provider

A **service provider** exposes runtime state that SQL cannot reach — execution lanes, traces,
file trees, drafts. A route binds one instead of a SQL file:

```yaml
sources:
  main:
    sql:
      service: ops.lanes
```

The interface is one method:

```java
@FunctionalInterface
public interface ServiceProvider {
    Object invoke(Map<String, Object> params);
}
```

Three rules the runtime holds you to:

- **Return template-ready data.** Maps, lists, and scalars only, with display formatting —
  status classes, indents, ISO timestamps — already computed. There is no serializer to
  configure.
- **Query routes must be side-effect free.** A provider bound on a command route may perform
  runtime administration; one bound on a query route may not.
- **Steps cannot bind a provider.** `service:` is legal on any `sources:` entry,
  and is refused at build time inside a command's `steps:` — a transactional step must be a
  SQL file or a sequence.

A route that binds a provider is assessed `extended` and scores +1
([governance.md](governance.md)).

Providers are registered into the runtime registry, which means something has to do the
registering — rung 3.

## Rung 3: a runtime extension

A **runtime extension** installs Camel routes and beans into the runtime as it is assembled.
This is how the optional feature modules work: SAML, SCIM, and OIDC are all runtime
extensions, so the runtime carries no compile-time dependency on any of them.

```java
public interface RuntimeExtension {
    String name();                                   // for diagnostics, logs, and the allowlist
    boolean enabled(AppConfig config);               // usually one config key
    void install(ExtensionContext context) throws Exception;
}
```

`install` runs **after** the core beans are bound — datasources, security, session store,
identity service and realm — and **before** the Camel context starts. `ExtensionContext`
gives you:

| | |
| --- | --- |
| `camel()` | the context being assembled: add routes, use the registry |
| `manifest()` | the app manifest, including its configuration and app home |
| `dataSource()` | the main datasource — business and identity data |
| `frameworkDataSource()` | ambient framework state ([framework datasource](deployment.md#framework-datasource)) |
| `bean(name, type)` | look up a framework bean already bound |
| `bind(name, bean)` | bind your own |

Register the implementation in
`META-INF/services/io.tesseraql.compiler.ext.RuntimeExtension`. `OidcRuntimeExtension` in
this repository is a complete worked example, at about the size a real extension runs to.

### Two ways in

An extension is discovered from the **runtime classpath** — a jar added with
`--modules`, or a dependency of your own runtime build — or from the **`plugins/` directory**
of the application the runtime serves. The classpath route is simpler and needs no signing;
`plugins/` is the one that travels with the application.

The framework's own extensions — OIDC, SAML and SCIM — are already on the runtime classpath and
need neither route: configuration alone turns them on
([module-channel.md](module-channel.md) decision 2). Both routes above are for extensions you or
a third party write.

`plugins/` is read from that one application. Extensions are a host decision: they register
routes and beans on the runtime, which is runtime-wide, so a runtime hosting an application
loads that application's plugins and no others. Under
[`tesseraql host`](hosting.md) each application has its own runtime, and therefore its own
`plugins/`.

### Signing a plugin jar

Every jar in `plugins/` must carry a detached Ed25519 signature beside it, `<jar>.sig`,
holding the base64 signature over the jar bytes. Generate a key pair once:

```sh
openssl genpkey -algorithm ed25519 -out plugin-signing.pem
openssl pkey -in plugin-signing.pem -pubout -outform DER | base64 -w0   # the trustedKeys value
```

Sign each jar at build time:

```sh
openssl pkeyutl -sign -inkey plugin-signing.pem -rawin -in my-plugin.jar | base64 -w0 \
  > my-plugin.jar.sig
```

Then configure the app:

```yaml
tesseraql:
  plugins:
    dir: plugins                   # relative to the app home; *.jar beside *.jar.sig
    trustedKeys:
      - MCowBQYDK2Vw...            # the base64 public key printed above (PEM also accepted)
    requireSignature: true         # false skips verification — development only
    allowlist: [saml, scim]        # when present, only these extension names may install
```

Keep the private key out of the repository. It signs code that runs inside your application.

### What the runtime does with it

Each verified jar gets **its own isolated class loader**, so plugins cannot see each other's
classes. The `allowlist` applies to every discovered extension, classpath and plugin alike —
a jar that arrives on the classpath unnoticed cannot install itself past the configuration.

Failures are explicit rather than silent:

| Code | Meaning |
| --- | --- |
| `TQL-PLUGIN-1301` | Signature verification is on and no trusted keys are configured. |
| `TQL-PLUGIN-1302` | A jar has no `<jar>.sig` beside it. |
| `TQL-PLUGIN-1303` | A signature does not verify against any trusted key. |

An extension not on the allowlist is skipped with a warning naming it, rather than installed
quietly.

## Choosing a rung

- **Can a declaration do it?** Use the declaration. The reference is generated, so if it is
  not there, it is not there.
- **Is it one predicate inside an expression?** Rung 1. It stays lock-pinned and reviewable.
- **Does a route need runtime state SQL cannot see?** Rung 2, and accept the `extended` mode.
- **Do you need routes or beans the framework does not have?** Rung 3 — and prefer the
  classpath over `plugins/` unless the extension must travel with the application.

If you are building something other people will install, stop at rung 0. That is what the
admission profile means by declarative-only, and it is checked rather than trusted.

Admission is opt-in: it is the bar for an application someone else runs without reviewing it.
An application you deploy yourself, or one a vendor ships to a customer who has read the
contract, may use every rung — the profile is a gate for distribution, not a house style.

## Next

- [governance.md](governance.md) — what each rung costs on an everyday build.
- [admission.md](admission.md) — why distribution stops at rung 0.
- [declarative-validation.md](declarative-validation.md#custom-functions) — the expression
  function recipe in full.
