// The tql/email fragment palette (docs/notifications.md "HTML mail") for mail-template
// completion. The signature set mirrors the framework's bundled library, which is
// drift-guarded by BundledEmailTemplatesTest — a regen that changes the palette updates
// both sides deliberately.

export interface EmailFragment {
  readonly name: string;
  readonly params: readonly string[];
  readonly library: 'hc-email' | 'hc-email-layout';
}

export const EMAIL_FRAGMENTS: readonly EmailFragment[] = [
  { name: 'hcButton', params: ['href', 'label'], library: 'hc-email' },
  { name: 'hcButtonSecondary', params: ['href', 'label'], library: 'hc-email' },
  { name: 'hcHeading', params: ['text'], library: 'hc-email' },
  { name: 'hcSubheading', params: ['text'], library: 'hc-email' },
  { name: 'hcText', params: ['text'], library: 'hc-email' },
  { name: 'hcTextMuted', params: ['text'], library: 'hc-email' },
  { name: 'hcLink', params: ['href', 'label'], library: 'hc-email' },
  { name: 'hcSeparator', params: [], library: 'hc-email' },
  { name: 'hcBadge', params: ['label'], library: 'hc-email' },
  { name: 'hcBadgeInfo', params: ['label'], library: 'hc-email' },
  { name: 'hcBadgeSuccess', params: ['label'], library: 'hc-email' },
  { name: 'hcBadgeWarning', params: ['label'], library: 'hc-email' },
  { name: 'hcBadgeError', params: ['label'], library: 'hc-email' },
  { name: 'hcAlertInfo', params: ['title', 'text'], library: 'hc-email' },
  { name: 'hcAlertSuccess', params: ['title', 'text'], library: 'hc-email' },
  { name: 'hcAlertWarning', params: ['title', 'text'], library: 'hc-email' },
  { name: 'hcAlertError', params: ['title', 'text'], library: 'hc-email' },
  { name: 'hcPanel', params: ['content'], library: 'hc-email' },
  { name: 'hcKvTable', params: ['rows'], library: 'hc-email' },
  { name: 'hcFooter', params: ['text'], library: 'hc-email' },
  { name: 'hcLayout', params: ['title', 'preheader', 'content'], library: 'hc-email-layout' },
];

/** The mail render model's fixed roots and the event members (MailNotifier's contract). */
export const MAIL_MODEL_ROOTS = ['payload', 'event'] as const;
export const EVENT_MEMBERS = ['id', 'source', 'app'] as const;

export type EmailCompletionContext =
  | { kind: 'fragment'; library: 'hc-email' | 'hc-email-layout' }
  | { kind: 'root' }
  | { kind: 'event-member' };

/**
 * What to complete at a position in a mail template line: a fragment name after
 * `~{tql/email/<library> :: `, an `event.` member, or a `${` model root.
 */
export function emailCompletionAt(lineText: string, character: number):
    EmailCompletionContext | undefined {
  const before = lineText.slice(0, character);
  const fragment = /~\{tql\/email\/(hc-email(?:-layout)?)\s*::\s*(\w*)$/.exec(before);
  if (fragment !== null) {
    return { kind: 'fragment', library: fragment[1] as 'hc-email' | 'hc-email-layout' };
  }
  if (/\$\{\s*event\.\w*$/.test(before)) {
    return { kind: 'event-member' };
  }
  if (/\$\{\s*\w*$/.test(before)) {
    return { kind: 'root' };
  }
  return undefined;
}
