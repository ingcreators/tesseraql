import * as vscode from 'vscode';

/**
 * The serve status bar item (docs/vscode-extension.md, Phase 55 slice 5): polls the
 * Phase 45 readiness probe on the configured base URL while an app home is open —
 * up, DOWN (a 503 readiness answer), or offline (unreachable). One click opens the
 * app. Nothing new server-side; the probe is the contract.
 */
export class ServeStatus {
  private readonly item: vscode.StatusBarItem;
  private timer: NodeJS.Timeout | undefined;

  constructor() {
    this.item = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 0);
    this.item.name = 'TesseraQL serve';
    this.item.command = 'tesseraql.openServer';
  }

  dispose(): void {
    this.stop();
    this.item.dispose();
  }

  start(): void {
    this.stop();
    this.timer = setInterval(() => void this.probe(), 10_000);
    void this.probe();
    this.item.show();
  }

  stop(): void {
    if (this.timer !== undefined) {
      clearInterval(this.timer);
      this.timer = undefined;
    }
    this.item.hide();
  }

  static serverUrl(): string {
    const url = vscode.workspace.getConfiguration('tesseraql')
        .get<string>('serverUrl', 'http://localhost:8080');
    return url.replace(/\/+$/, '');
  }

  private async probe(): Promise<void> {
    const base = ServeStatus.serverUrl();
    try {
      const response = await fetch(base + '/_tesseraql/health/ready',
          { signal: AbortSignal.timeout(3_000) });
      if (response.ok) {
        this.item.text = '$(pulse) TesseraQL: up';
        this.item.tooltip = `${base} is serving and ready — click to open`;
        this.item.backgroundColor = undefined;
      } else {
        this.item.text = '$(warning) TesseraQL: DOWN';
        this.item.tooltip = `${base} answered ${response.status} on the readiness probe`;
        this.item.backgroundColor = new vscode.ThemeColor('statusBarItem.warningBackground');
      }
    } catch {
      this.item.text = '$(circle-slash) TesseraQL: offline';
      this.item.tooltip = `${base} is not reachable — run TesseraQL: Serve`;
      this.item.backgroundColor = undefined;
    }
  }
}
