# TesseraQL

TesseraQL is a SQL-first hypermedia and integration framework.

This repository is initialized with:

- Java 21 baseline / Java 25 compatibility policy
- Maven multi-module skeleton
- Dev Container configuration for Java, Maven, Docker/Testcontainers, Node.js, pnpm
- VS Code extension recommendations for Java, YAML, Docker, Codex, and Claude Code
- Coding-agent guidance for Codex / Claude Code
- Example external TesseraQL application layout

## Quick start

```bash
# Open in VS Code, then:
# Command Palette -> Dev Containers: Reopen in Container

mvn -B -ntp verify
```

Optional: generate Maven Wrapper after opening the devcontainer:

```bash
./scripts/bootstrap-maven-wrapper.sh
```

Then use:

```bash
./mvnw -B -ntp verify
```

## Repository layout

```text
tesseraql/
  .devcontainer/
  .github/
  .mvn/
  docs/
  examples/
  scripts/
  tesseraql-bom/
  tesseraql-core/
  tesseraql-yaml/
  tesseraql-compiler/
  tesseraql-camel-components/
  tesseraql-camel-runtime/
  tesseraql-camel-spring-runtime/
  tesseraql-security/
  tesseraql-test-core/
  tesseraql-coverage-core/
  tesseraql-report/
  tesseraql-studio/
  tesseraql-cli/
  tesseraql-maven-plugin/
```

## Java policy

TesseraQL 1.x uses Java 21 as the baseline and tests Java 25 compatibility.

- Build target: `--release 21`
- CI: Java 21 required, Java 25 compatibility check
- Virtual Threads are available on the Java 21 baseline.
