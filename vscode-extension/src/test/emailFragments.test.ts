import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  EMAIL_FRAGMENTS,
  emailCompletionAt,
} from '../core/emailFragments';

test('fragment completion fires after the library reference marker', () => {
  const line = '<div th:replace="~{tql/email/hc-email :: hcT';
  const context = emailCompletionAt(line, line.length);
  assert.deepEqual(context, { kind: 'fragment', library: 'hc-email' });
});

test('the layout library completes its own fragment', () => {
  const line = '<div th:replace="~{tql/email/hc-email-layout :: ';
  const context = emailCompletionAt(line, line.length);
  assert.deepEqual(context, { kind: 'fragment', library: 'hc-email-layout' });
  assert.ok(EMAIL_FRAGMENTS.some(
      (fragment) => fragment.library === 'hc-email-layout' && fragment.name === 'hcLayout'));
});

test('event members complete inside ${event.}', () => {
  const line = "    <div th:replace=\"~{tql/email/hc-email :: hcFooter(|Sent by ${event.";
  assert.deepEqual(emailCompletionAt(line, line.length), { kind: 'event-member' });
});

test('model roots complete inside ${}', () => {
  const line = '<p th:text="${pa';
  assert.deepEqual(emailCompletionAt(line, line.length), { kind: 'root' });
});

test('a half-typed Japanese identifier keeps its completion context', () => {
  // The identifier contract (docs/unicode-identifiers.md): Unicode names are names.
  const fragment = '<div th:replace="~{tql/email/hc-email :: 明細';
  assert.deepEqual(emailCompletionAt(fragment, fragment.length),
      { kind: 'fragment', library: 'hc-email' });
  const root = '<p th:text="${受注';
  assert.deepEqual(emailCompletionAt(root, root.length), { kind: 'root' });
  const member = '<p th:text="${event.受';
  assert.deepEqual(emailCompletionAt(member, member.length), { kind: 'event-member' });
});

test('ordinary markup completes nothing', () => {
  for (const line of ['<div class="hc-card">', 'plain text', '<p th:text="${payload.name}">done</p>']) {
    assert.equal(emailCompletionAt(line, line.length), undefined);
  }
});

test('the palette mirrors the drift-guarded library signature set', () => {
  assert.equal(EMAIL_FRAGMENTS.length, 21);
  const button = EMAIL_FRAGMENTS.find((fragment) => fragment.name === 'hcButton');
  assert.deepEqual(button?.params, ['href', 'label']);
  const separator = EMAIL_FRAGMENTS.find((fragment) => fragment.name === 'hcSeparator');
  assert.deepEqual(separator?.params, []);
});
