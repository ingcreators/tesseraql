// Studio deep links (docs/vscode-extension.md, Phase 56 slice 3): the running
// Studio's source view is addressed by app-relative path — the same URL every
// Studio surface links to.

export function studioSourceUrl(serverUrl: string, appRelativePath: string): string {
  const base = serverUrl.replace(/\/+$/, '');
  return base + '/_tesseraql/studio/ui/source?path=' + encodeURIComponent(appRelativePath);
}
