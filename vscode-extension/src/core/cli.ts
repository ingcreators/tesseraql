// How the extension invokes the project-selected CLI (docs/vscode-extension.md):
// headless lint parses the JSON contract; every other verb runs visibly in the
// integrated terminal with the app home as the working directory.

export function lintArgs(appHome: string): string[] {
  return ['lint', '--app', appHome, '--format', 'json'];
}

/** The command line a terminal runs for a CLI verb, with cwd at the app home. */
export function terminalCommand(cliPath: string, verb: string): string {
  const cli = /\s/.test(cliPath) ? `"${cliPath}"` : cliPath;
  // dev runs the stack (discovered one level up from the app home); every other verb
  // operates on this one application.
  return verb === 'dev' ? `${cli} dev` : `${cli} ${verb} --app .`;
}
