# Contributing

## Local development

Use the Dev Container.

```bash
./mvnw -B -ntp verify
```

## Before pushing

`mvn verify` is the Java half of what CI gates on. Two more jobs run outside Maven, and a
docs-only or extension-only change passes `verify` and fails them:

- `cd docs-site && pnpm run build` — the navigation manifest (every `docs/*.md` mapped or
  excluded in `nav.mjs`), the prose lint (no sentence over 60 words), and the site's link
  validation ([docs/docs-site.md](docs/docs-site.md)).
- `cd vscode-extension && pnpm run test && pnpm run package` — the extension's tests and the
  `.vsix` smoke package.

`scripts/run-ci-local.sh` runs all three. Formatting is `./mvnw spotless:apply`; the
reference pages regenerate with `./mvnw -q -pl tesseraql-docs-reference exec:java`
([docs/build.md](docs/build.md)).

## Java

TesseraQL 1.x uses Java 25 as the build baseline (docs/jvm-baseline.md).

## Commit scope

Keep generated artifacts out of source commits unless the change is explicitly about generated artifact reproducibility.
