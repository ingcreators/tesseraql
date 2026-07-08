// TQL-<DOMAIN>-<n> literals link to the published error-code reference — the
// generated index the documentation portal commits as docs/reference-error-codes.md
// and the docs site serves per domain section.

export const TQL_CODE_PATTERN = /TQL-[A-Z]+-[0-9]+/;

export function errorCodeDocsUrl(code: string): string | undefined {
  const match = /^TQL-([A-Z]+)-[0-9]+$/.exec(code);
  if (match === null) {
    return undefined;
  }
  return `https://ingcreators.com/tesseraql/reference-error-codes/#${match[1].toLowerCase()}`;
}
