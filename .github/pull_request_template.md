## Summary

## Checks

- [ ] `./mvnw -B -ntp verify`
- [ ] `cd docs-site && pnpm run build` when `docs/` changed (the navigation manifest and the prose lint run there, not under Maven)
- [ ] `cd vscode-extension && pnpm run test && pnpm run package` when the extension changed
- [ ] `./mvnw -q -pl tesseraql-docs-reference exec:java` when a command, option, config key or error code changed (the reference pages regenerate; `GeneratedReferenceTest` fails until they do)
- [ ] CHANGELOG entry for a behavior change (AGENTS.md rule 10)
- [ ] No secrets or local credentials added
- [ ] Module boundaries are respected
- [ ] Design docs updated if behavior changed
