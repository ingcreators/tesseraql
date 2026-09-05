# The 2-way SQL parser and the identifier contract

> **Status: design.** Written 2026-09-05 and measured against main at `d620bc1ae`, from the
> remediation plan for the 2026-09-04 whole-repo audit (findings F17-F26).
>
> **Slice 1 shipped before this document, as #1148.** The `('')`-swallows-the-statement defect
> (F17) was a three-line fail-open in the framework's most load-bearing primitive, it depended on
> nothing written here, and its reviewer said to order it ahead of the design. It did. This
> document records that decision retroactively and builds on the `skipParenGroup` that shipped.
>
> **The plan this document replaces was re-measured against `d620bc1ae` and was wrong in eleven
> places.** Read the decisions below, not the plan. The measured corrections are recorded in
> [What the plan got wrong](#what-the-plan-got-wrong) so that nobody re-derives them from a stale
> document.

## The cause, stated once

`Sql2WayParser` has three lexical layers, and each one of them guesses somewhere it should check.

| Layer | Where | What it does | How it guessed |
| --- | --- | --- | --- |
| Statement | `parseBlockBody` | emits `Text` nodes | `'` is opaque; `"` and `` ` `` are not |
| Dummy | `skipDummy` / `skipParenGroup` | discards the SQL-tool placeholder | three ad-hoc shapes, no doubling escape, silent at EOF |
| Directive | `readComment` / `Directive.keyword()` | splits keyword from argument | splits on the first *space*, not the first *whitespace* |

The three layers share no code, and that is correct: one emits, one discards, one splits. What they
must share is a *contract*, and the campaign's premise is that every failure in the table above is
one of two shapes.

**A scan that stops without saying so.** F17 was one: the paren scanner ran to end of input with
the group still open and returned without a word, so `where code in /* codes */ ('') and tenant_id
= /* t */ 1` rendered as `where code in (?, ?)` — every predicate after the dummy dropped, a tenant
guard among them. It shipped as #1148. Two more of the same shape are still open, and both were
filed as nuisances rather than as fail-opens, which the re-measurement corrected:

- `where q = /* q */ 'oops` does not "parse cleanly" (F21). It truncates every character after the
  opening quote to end of input. `select * from t\nwhere q = /* q */ 'oops\nand tenant_id = 7`
  renders `select * from t\nwhere q = ?`.
- `select "a--b" from t where id = /* id */ 1` does not merely raise a confusing error (F22). The
  bind site is deleted and the dummy literal reaches the database: rendered SQL unchanged,
  `params=0`. A soft-delete guard written that way is evaluated against its own dummy.

**A rule stated in one place and copied in five.** The identifier class
`[\p{L}_][\p{L}\p{N}_]*` is the framework's injection defense and the reason names land in
generated SQL unquoted. It is inlined by two lint families and by the framework's own JavaScript,
so widening the authority class alone would leave the write-scope security lint blind to exactly
the names the contract newly admits. The single-space directive split is copied the same way, into
the linter and into the coverage manifest.

## Decisions

### 1 — Three layers, one contract, three implementations

The layers stay separate. They are held to the same *behaviour* and deliberately not to the same
code, because each has a different job with the run it reads: the statement layer appends it, the
dummy layer discards it, `SqlTableReferences.tokenize` scans it for table names.

The shared contract is stated once, here, and every layer is tested against it:

> **pos is on the opening delimiter. A doubled delimiter is the only escape. End of input is an
> error.**

### 2 — Two quote scanners, named apart

`consumeStringLiteral(StringBuilder, char)` at the statement layer and `skipQuotedRun(char)` at the
dummy layer. Two names, not two overloads of one name: an overload pair where one appends and one
discards is the same two-contracts-one-name confusion that caused F17.

The half of the contract #1148 shipped is `pos` on the opening delimiter. The other two halves
arrive with the dummy grammar: today `skipQuoted()` has no notion of the `''` escape, and its
javadoc claims otherwise — a comment that is true of the loop that calls it and false of the method
itself.

### 3 — A scalar dummy is one grammar

An optional `+`/`-`, then a maximal run of `Character.isJavaIdentifierPart` plus `.`, absorbing a
`+`/`-` that immediately follows `e` or `E`. One rule covers `1`, `-1`, `1.5e-3`, `0x1F`, `true`,
`null`, `current_timestamp` and `t.col`, where the old digit branch left `x1F` behind and the old
word branch could not see a number at all. Then two adjacent suffixes with no whitespace: a
balanced call group, so `/* d */ now()` stops leaving `()` in the SQL, and a quoted run, so
`N'…'`, `X'…'` and `_utf8'…'` are one dummy.

A whitespace-separated quoted run is consumed only after one of fourteen type keywords (`date`,
`time`, `timestamp`, `timestamptz`, `datetime`, `interval`, `decimal`, `numeric`, `uuid`, `json`,
`jsonb`, `bytea`, `bit`, `binary`), case-insensitively. That is the standard typed-literal form
`DATE '2024-01-01'`, and the whitelist is what keeps `select /* x */ x 'alias'` from losing an
alias.

### 4 — A bind site must have a dummy, and the expression is diagnosed first

A bind with no dummy is `TQL-SQL-2102`. The plan justified this as plain-SQL-tool runnability. The
re-measurement found a better reason: it is not cosmetic. `select /* a */, /* b */ from t` renders
`select ?, ? t` today — the bare-word scanner eats the `from` keyword as `/* b */`'s dummy, with no
error.

`parseBind` computes and parses the expression **before** it demands the dummy. Studio's SQL
builder emits `insert into <t> (/* TODO: columns */)` as an author-fills-this-in placeholder, and
those must keep reporting the malformed expression rather than the missing dummy. That reorder is
worth doing on its own terms, because today's expression diagnostic is bare — `Unexpected character
':'`, with no line and no expression text — and the fix that follows it should carry a location.

### 5 — `'`, `"` and `` ` `` are opaque; `[` is not

Backtick joins because MySQL is a supported dialect and `` `order` `` is its idiomatic quoting, and
its escape is a doubled delimiter like the others. `[` deliberately does not join: in DuckDB and
PostgreSQL it is list and array syntax, and making it opaque would swallow a directive written
inside an array constructor.

The consequence, which the plan never stated: `select x as [Owner's name] from t` is refused today
with `TQL-SQL-2102` and stays refused. A SQL Server author writes `"Owner's name"`, which this
campaign makes work. Bracket quoting is not a supported spelling in a 2-way template, and saying so
once is cheaper than a dialect-conditional lexer.

### 6 — The two lexers are held to their behaviour by a test, not by a comment

`SqlTableReferences.tokenize` is a token scanner for dependency analysis; `Sql2WayParser` is a
template splitter. A parameterized test feeds one corpus of quoting cases to both and asserts the
same opacity decision on every well-formed run.

Two divergences are written into that test as positive assertions rather than as skips, because
both are deliberate:

- **`[` is a bracket-quoted identifier to the scanner and array syntax to the splitter.** The
  scanner sees a table reference where the template splitter sees a list constructor.
- **An unterminated run is a parse refusal and a scanner degradation.** `SqlTableReferences`
  returns leniently at end of input by its own documented contract, because a dependency scan of a
  broken file should still name the tables it found.

The test must not compare token *text*: the scanner keeps doubled delimiters unescaped and drops
the last character of an unterminated run, neither of which is this campaign's subject.

### 7 — A directive keyword ends at the first whitespace, everywhere it is read

`Directive.keyword()` splits on `content.indexOf(' ')`, so `/*%if\n  q != null\n*/` is reported as
`Unknown directive 'if\n'` — a directive that does not exist, for the natural way to write a long
guard. The keyword scan becomes "to the first `Character.isWhitespace`".

The three sub-keyword splits (`separator`, `on`, `as boolean`) widen to `\s+` with it. **The plan's
reason for that was false and the true reason is worse.** With the two-space indentation the plan
itself writes, `lastIndexOf(" separator ")` and `indexOf(" on ")` and `endsWith(" as boolean")` all
still match, because the indentation puts a space immediately before the sub-keyword. What actually
breaks is the un-indented and tab forms, and two of them break silently:

- `/*%scope s\nas boolean */` parses with `asBoolean=false`. The row-level masking flag is lost and
  the directive quietly degrades to a WHERE predicate.
- `/*%scope s\non u */` parses with the whole thing as the scope name and no alias.

**The split is copied into two more modules, and the plan named neither.** `ScopeRules.java:211`,
`:342`, `:385` and `ManifestCoverage.java:255`, `:259` read the same directive with the same
single-space assumption. Widening only the parser makes the linter emit a spurious
`TQL-SCOPE-3011 UNDECLARED_SCOPE` for a directive the parser now accepts, and makes scope item
coverage mis-attribute. All three modules move together, behind one shared primitive.

### 8 — `else` ends an if-chain

`parseIf` accepts terminators after the null-condition branch, so an `elseif` or a second `else`
after the `else` parses, ships, and can never run: `renderIf` takes the first branch with no
condition, sets `done`, and never evaluates the rest.

Both are now `TQL-SQL-2102`. `renderIf` is untouched — with the parser refusing, its `done` flag is
belt-and-braces rather than the thing hiding the defect.

The coverage claim needs care, and the plan's version of it is not true. The mechanism is real: an
unreachable branch never enters the branch *denominator*, so the report cannot distinguish a
three-branch file with a dead arm from a well-formed two-branch chain. But the file does not report
"fully covered" — `SqlCoverableLines` walks the unreachable body, so line coverage falls and
`CoverageGate` can fire with a shortfall nobody can attribute. That is the honest statement.

### 9 — An empty list under NOT IN is refused, and `empty` learns to answer

`appendListBind` renders `(null)` for an empty collection. That is right for `IN` — it matches no
rows — and exactly inverted for `NOT IN`, where `x NOT IN (NULL)` is UNKNOWN for every row, so an
exclusion filter with an empty list hides everything instead of nothing. The renderer never sees
the operator.

Every rendering fix was considered and rejected. No constant list makes `x NOT IN (…)` true for all
x. `(null) or 1=1` is worse than the bug, because `or` binds looser than `and` and turns the whole
WHERE into a tautology. `not in (select null where 1=0)` needs `FROM DUAL` on Oracle, which puts a
dialect split into the most load-bearing primitive in the framework.

So the shape is this repository's established runtime-code plus lint-twin: the parser marks the
bind negated by scanning back over the raw source from the comment start, the renderer refuses an
empty negated list with `TQL-SQL-2118`, and `TQL-SQL-2119` catches an unguarded negated list at
build time so it never reaches a request.

**The guard idiom the refusal names had to change, because the plan's was fail-open on the plan's
own headline case.** `EvaluationContext.virtualProperty` answers `empty` for a Collection, a Map
and a CharSequence — and not for `null`, and not for a Java array. So `!codes.empty` is `true` when
`codes` is absent, and an unselected multi-select renders `not in (null)` through the very guard
the message recommended. `InputBinder` never puts an absent optional `type: array` input into the
bound map, so absent is exactly what the headline case produces.

`virtualProperty` is fixed rather than worked around: `empty` answers `true` for `null` and reads
the length of an array, which is what its sibling `size` already does. The asymmetry was the bug.
Every test in this campaign that binds a list runs the four shapes — absent/null, empty array,
empty list, non-empty.

### 10 — `TQL-SQL-2118` answers 500, and says so out loud

It is thrown at render time, inside a request, and nothing in the build asks the question:
`StatusMappingLedgerTest` scans the LD domain and 4000-4999, so a `TQL-SQL-2xxx` code raised in a
request answers 500 by default with nothing to notice.

500 is the honest answer and it is written explicitly, not left to the default. The template is
defective: a route that can bind an empty list under `not in` is a route whose author has not
guarded it, and `TQL-SQL-2119` is the real fix, refusing that route at build time. An explicit
`case` carrying that reason is what stops the next reader from "fixing" it to a 4xx.

### 11 — The identifier contract admits combining marks

`[\p{L}_][\p{L}\p{N}_]*` excludes `\p{M}`, which every abugida requires and which decomposed text
produces for scripts that have a composed form. Measured at HEAD, `isIdentifier` is false for
`ग्राहक`, `مُحَمَّد`, `ยิ้ม` and decomposed `Việt` and `が`, while the 2-way bind lexer —
`Character.isJavaIdentifierPart` — accepts every one of them. `docs/identifiers.md` promises "every
other script".

    START      = [\p{L}_]
    PART       = [\p{L}\p{Mn}\p{Mc}\p{N}_]
    IDENTIFIER = START PART*

A mark may not **start** an identifier: every real name begins with a letter, and a leading
combining mark is a rendering trick. `\p{Me}` (enclosing marks) is excluded as display-only, and
`\p{Cf}` (ZWJ, ZWNJ) because an invisible character in a name that lands unquoted in SQL text is a
spoofing surface.

**The injection argument extends, and this is measured rather than asserted.** Over all 2488
`\p{Mn}`+`\p{Mc}` code points: none is ASCII, none normalizes under NFC, NFD, NFKC or NFKD to an
ASCII non-alphanumeric, and none case-maps to one. A combining mark cannot close a quote, open a
comment or terminate a statement, so identifiers still land unquoted. Both engines available
locally accept them unquoted and return the label unchanged.

**The plan's claim that this "lands exactly on `Character.isJavaIdentifierPart` minus the
invisibles" is false by 987 code points, and the true numbers are better.** The widening closes
2488 of the 2786 code points by which the contract was narrower than the lexer — every `\p{Mn}` and
`\p{Mc}` there is. What remains is 298 the lexer accepts and the contract refuses (56 `\p{Cc}`, 170
`\p{Cf}`, 9 `\p{Pc}`, 63 `\p{Sc}` including `$`), and 915 `\p{No}` the contract accepts and the
lexer refuses. The `\p{Cc}`/`\p{Cf}` half is deliberate. The other 72 and the 915 are a
pre-existing divergence, recorded below and not closed here — narrowing `\p{N}` would be a refusing
change riding a widening one, and admitting `$` unquoted into SQL text is a dialect hazard, since
`$` opens dollar-quoting in PostgreSQL.

**The lexer is not the specification.** The contract is narrower on purpose; the campaign closes
the gap that hurts and states the rest.

### 12 — A decomposed name is accepted and never normalized; a lint catches the pair

This is the decision the widening forces, and it is narrower than it looks.

Neither engine normalizes: selecting an NFD spelling against an NFC-declared column fails on both
(`Column "VIỆT" not found`). So accepting NFD buys a name that works when it is spelled the same
way everywhere. It never buys cross-form matching, and normalizing at a compile boundary would make
a column genuinely declared in NFD unreachable — a conversion layer, which is the one thing
`docs/identifiers.md` promises there is none of.

Measured, three of the four places a mismatch could bite do not:

- The path-parameter **name** never travels on the wire; `WireNames` rewrites it to `p0`.
- A literal Unicode path segment does travel, and an NFD request against an NFC-declared literal
  404s — but that is today's behaviour and the widening does not touch it.
- A `key:` column missing from a result row is `RowTokens.encode` throwing, loudly, before and
  after.

**The one that bites is the declared name in the `.yml` against the `/* name */` bind in the
`.sql`.** Two files, plausibly two editors on two operating systems, joined by exact string
equality in `RequestBinder`, with a null bind and no diagnostic on a mismatch. Today that pair is
protected by accident, because `isIdentifier` refuses the decomposed half loudly. The widening
removes the accident.

So the accidental protection is replaced by a deliberate one, at build time rather than at request
time: **the linter reports a declared name and a bind name that differ only by normalization form**
(`TQL-SQL-2121`). It has both halves already — `LintSupport.ambientBinds` collects the bind
expression sources and the definition carries the declared names. No normalization happens
anywhere; a check does not mutate.

### 13 — The contract has one home, and a source scan keeps it that way

The finding was one regex. The damage was the copies. `ScopeRules.SCOPED_TABLE_ALIASED`,
`WRITE_TARGET` and its from-fallback and `ViewRules.PLAIN_COLUMN` rebuild `[\p{L}_][\p{L}\p{N}_.]*`
by hand, and `SqlIdentifiers.QUALIFIED` is both shared and stricter — the inline class accepts a
trailing dot.

A source-scanning test refuses a re-inline across `src/main/java` **and** `src/main/resources`. The
`resources` half is not padding: the framework's own JavaScript carries a fifth copy, and a guard
that certifies an inventory it cannot see is worse than none. The allowlist names its two
deliberate deviations with their reasons — `Sql2WayParser`'s file-name charsets and `StepContext`'s
dotted-path template placeholder are different grammars, not copies of this one.

`vscode-extension/src` is out of scope: a separate build with no access to the Java constant, where
a cross-tree guard would only teach people to add exceptions.

### 14 — Three ScopeRules defects are fixed before the contract moves, not with it

The plan treated the `ScopeRules` edit as a mechanical consequence of the widening. Measured, it is
not. Three defects stand on their own in a security lint at HEAD, independent of any contract
change:

1. **The aliased branch is dead** for a marked table name, because it is gated on the composed
   constant.
2. **Two marked tables sharing a first letter collide into one key**, because both sides truncate
   at the mark identically — which is also why the aliasless warning still fires today, and why the
   plan's stated reason for the coupling was wrong. The collision produces a *false*
   `TQL-SEC-4100`.
3. **The ALIAS lookahead does not terminate at a mark**, and false-matches.

They land first, in their own pull request, reviewable against `AppLinterScopeTest` alone. The
contract widening then lands with the copies already composed, so its diff is genuinely one regex
plus tests plus docs — which is what the plan claimed it was.

### 15 — One helper enumerates a definition's SQL, and the injection lint stops being main-only

`lintEmbeddedVariables` — the `TQL-SQL-2109` injection lint — reads `definition.main()` and returns.
A `/*# {x} */` embedded variable in a named source, a command step or a validation rule is not
checked at all, on routes, consumers and MCP tools alike. Measured over this repository: **44 of 85
definition-carried SQL slots are unchecked, on 43 distinct files — 52%.** The unchecked half of a
security lint is larger than the checked half.

Three new lints were about to be built beside it. So the enumeration becomes one helper first, and
`TQL-SQL-2109` is its first caller.

There are **seven** partial, mutually inconsistent enumerations in the lint package today, which is
the real argument for the helper. The complete slot list, which no existing enumeration has in
full:

    definition.sources()          → Binding.file() / params()          (includes main)
      the same bindings' enrich() → EnrichSpec.sql().file() / params()
    definition.steps()            → Binding.file() / params()   and enrich()
    definition.validate()         → ValidationRule.file() / params()
    definition.fileExport().after().sql() → Binding.SqlArm.file() / params()

`enrich:` on a **step** is linted by nothing today, on any rule. The helper does not filter; callers
do.

### 16 — A `params:` key is a bind name

`params: { order-id: query.order-id }` with a matching `/* order-id */` bind lints clean and runs
forever with a null bind, because the expression grammar reads `order-id` as the subtraction
`order - id` and both operands are unbound. Verified: `ExpressionParser.parse("order-id")` yields
`Arithmetic[SUB, Path[order], Path[id]]`, the render binds `null`, and the real `AppLinter` reports
nothing.

`TQL-SQL-2120` checks every `params:` key that becomes a SQL bind name. The gate is
`file() != null || contract() != null`, and both halves matter. Raw `Binding.params()` over-reaches
into `service:` arguments, which are the bean's parameter names and not bind names — an exclusion
already recorded in `AmbientPrincipalRules`. A `file() != null` gate under-reaches and silently
skips the `contract:` arm, whose params **are** bind names for the contract's SQL and which ships in
the gallery.

A lint and not a JSON Schema constraint, for a reason the re-measurement corrected: not because the
schemas are two copies, but because **the build has no JSON Schema validator at all**. The schemas
are editor-only. A constraint written there would be advice, not a gate.

`MatchArm.params`, `SqlRef.params` and `AssignSpec.params` — the scope and workflow document
families — carry real bind names and are **out of scope**, named here rather than left silent, and
filed below.

### 17 — A transaction rolls back on any `Throwable`, through one primitive

Three sites open a transaction, run a body, commit, and restore autocommit in a `finally`, and all
three catch a listed set of exceptions around their rollback. An `Error` from the body reaches the
`finally` with the transaction still open, where `setAutoCommit(true)` commits it — the
`Connection.setAutoCommit` contract, and what pgjdbc and Connector/J both do.

Measured at HEAD with a recording proxy: an `Error` from the body produces
`[getConnection, getAutoCommit, setAutoCommit(false), setAutoCommit(false), close]`. No rollback.
The `RuntimeException` control produces a rollback.

**The shape is fourteen sites, not three, and patching named sites has already failed to stop it
recurring: the transfer campaign added a fourteenth the day after the audit was written.** One of
the fourteen has no `catch` clause at all — `JdbcFileTransferService`'s CSV/Excel import, where any
failure whatsoever commits a partial import the code's own comments say must never happen.

So this is not fourteen fixes. It is one primitive in `tesseraql-core`, `Transactions.run`, that
owns "open, run, commit or roll back on any `Throwable`, restore autocommit", with an `Error`
rethrown unwrapped so it is never dressed as a coded, catchable, retryable failure. Fourteen call
sites become callers, and a ledger test refuses a fifteenth hand-rolled one.

Two mechanical notes that the plan got wrong and that cost a compile each. Widening a catch to
`Throwable` and adding `if (ex instanceof Error error) { throw error; }` does **not** narrow `ex`
on the negative side, so a following `asTqlException(ex)` taking an `Exception` is a type error —
proven with javac. And `SqlStatementTest`'s `FakeDatabase` answers `false` for `getAutoCommit`, so
`setAutoCommit(true)` is never recorded and an ordering assertion written against it compares -1
with -1.

### 18 — `RowTokens`' words move, not its check

`encode` refuses `null` or empty, while the class javadoc, the `@throws` clause and the thrown
message all promise "null, absent or blank" — so a whitespace-only value gets a token the
documentation says has none.

The code is right and the words move. A whitespace-only key is data: `JoinKeys.value` does not trim,
`decode` returns it byte-identically, and `SqlStep` depends on the refusal as its "the keyset page
ends" signal. Tightening the check to `isBlank()` would refuse a real key and could not be enforced
symmetrically in `decode`, which is how a contract acquires two answers.

The regen trigger is `ViewBinding`'s javadoc, not `TQL-VIEW-3322`'s message string — the reference
generator reads the javadoc above the constant. `docs/declarative-views.md` carries the same wrong
word by hand and moves with it.

## The slices

Thirteen pull requests. Order is by textual dependency in `Sql2WayParser.java`, smallest first, so
that the largest diff rebases onto settled code rather than the reverse.

| # | Slice | Closes | Size |
| --- | --- | --- | --- |
| 1 | This document, and the two internal-docs lists | — | M |
| 2 | A quoted identifier is opaque to the lexer | F22 | S |
| 3 | A directive's words are separated by whitespace, and `else` ends the chain | F23, F24 | M |
| 4 | The dummy is one grammar, and an unterminated one is an error | F21 | M |
| 5 | One helper enumerates a definition's SQL, and the injection lint sees all of it | — | S |
| 6 | An empty list under NOT IN is refused, not rendered | F20 | M |
| 7 | An unguarded negated list is a build error | F20 | M |
| 8 | A `params:` key is a bind name, and the linter says so | F25 | S |
| 9 | The write-scope lint sees the table it is guarding | — | S |
| 10 | The identifier contract admits combining marks, and a decomposed twin is a build error | F19 | M |
| 11 | A transaction rolls back on any failure, through one primitive | F18 | M |
| 12 | The remaining eleven transaction owners call the primitive | — | M |
| 13 | `RowTokens` refuses an empty key component, not a blank one | F26 | S |

**Slice 2 leads because its stated dependency on slice 4 is false.** The statement layer's
`consumeStringLiteral` already implements the doubling escape; slice 4 rewrites a different method
at a different layer. There is zero textual overlap and zero code dependency, and slice 2 closes a
silent fail-open while slice 4 is the campaign's largest grammar change.

**Slice 6 is last of the parser slices** because it is the only one that edits both `parseBind` and
`parseBlockBody` — the regions slices 2 and 4 both touch.

The codes this campaign mints, in the order they land: `TQL-SQL-2118` (slice 6, the render-time
refusal), `TQL-SQL-2119` (slice 7, the unguarded negated list), `TQL-SQL-2120` (slice 8, the
non-identifier `params:` key) and `TQL-SQL-2121` (slice 10, the declared name and bind name that
differ only by normalization form). Each is a generated-reference change, so those four slices are
sequenced rather than run concurrently.

## The guards

Construction closes what it can. The rest is held by four instruments, and each is blind where
another sees.

| Guard | Refuses | Blind to |
| --- | --- | --- |
| `noInputIsSilentlyTruncated` | a scan that stops without saying so | anything the generator does not produce |
| the lexer parity test | the two lexers disagreeing on a well-formed run | token text, and the two pinned divergences |
| the identifier source scan | a sixth inline copy of the character class | a copy that is not spelled like the others |
| `Transactions` ledger | a fifteenth hand-rolled transaction owner | what the body does inside one |

**The sentinel oracle is the instrument whose absence let F17 live since the initial engine
commit.** The existing fuzz test only checks the exception *type*, which silent truncation passes.
The oracle appends `\nselect zz_sentinel_zz, 1\n` to every generated input and asserts the parse
either raises or keeps the sentinel.

It is measured, not hoped for: at the plan's own seed it fails on 4 of 4000 inputs, and over 100,000
inputs at five seeds it fails 123 times — every one of them explained by the scalar quoted run's
quiet return at end of input, and every one of them fixed by slice 4. That is why it rides slice 4
and could not have ridden slice 1: it would have landed red. The four literal inputs are pinned as
cases beside the generative net, because a seeded assertion that fires on 0.1% of inputs is a net,
not a proof.

## What this breaks

Every refusal in this campaign is additive against everything in the repository. The survey was
re-run at `d620bc1ae` over all 416 tracked `.sql` files and their 533 bind sites:

- no odd `"` or backtick parity, with comments and strings stripped; the eight files carrying
  backticks carry them inside `--` comments, which are matched first
- no typed-literal, call, prefixed, doubled-quote or hex dummy, and no dummy-less bind site
- no `/*%else*/` anywhere
- no list bound under `not in`
- no non-identifier `params:` key, and none carrying a combining mark
- a directive-set simulation of the proposed lexer changes behaviour in 0 of 416 `.sql` files, 0
  Java SQL fixtures and 0 YAML documents

The exposure is entirely in applications outside this repository, and every change is in the
fail-loud direction — except the identifier widening, which only admits.

## Recorded deviations

Permanent exceptions and known gaps, not approvals.

1. **PostgreSQL `E'a\'b'` and Oracle `q'[…]'` are not understood.** The backslash escape and
   alternative quoting are not the doubling escape. Both stop at the first unescaped delimiter,
   which is a loud parse error rather than a silent one.
2. **A `--` remark is skipped inside a paren dummy group and not after a scalar one**, so
   `/* d */ -- x` scans as a bare-word dummy. Not reachable in any template in the tree.
3. **A `not in` split across a directive boundary is not detected** by the backwards scan, because
   the text buffer is empty there.
4. **`[` diverges between the two lexers** by decision 5, and `select x as [Owner's name] from t`
   stays refused.
5. **The contract accepts 915 `\p{No}` the lexer refuses and refuses 72 `\p{Pc}`/`\p{Sc}` it
   accepts**, `$` among them. Pre-existing, unexamined, and left that way deliberately by decision
   11.
6. **NFD and NFC spellings of one name are two identifiers**, in this framework and in every engine
   measured. Decision 12.
7. **`MatchArm`, `SqlRef` and `AssignSpec` `params:` maps are not checked** by `TQL-SQL-2120`.
8. **`WireNames.WIRE_SAFE` is stricter than the router requires.** Its javadoc says Vert.x rejects
   `{order_id}`; Vert.x 5.1.6 accepts `:order_id` and even `:2fast`. Harmless — a stand-in is
   minted where none was needed — but the stated reason is wrong.

## Filed, not fixed

- **A `/*%if*/`-guarded site cannot be exercised as a declarative-suite `sql` case.** An empty
  collection binding *is* expressible, so the idiom `TQL-SQL-2118` recommends can be covered; a
  *refusal* is not assertable. Same shape as the edit-conflict campaign's recorded gap that a
  `/*%lock*/` statement cannot be a suite `sql` case. One `tesseraql-test-core` slice closes both.
- **`enrich:` declared on a step is linted by nothing**, on any rule. Surfaced by decision 15's
  enumeration; not closed by it.
- **The expression parser has no non-truncation oracle.** Measured green today, so it is a net
  rather than a fix, and it inspects the returned expression rather than the node tree.
- **The scope name is not validated as an identifier**, though the alias is.
- **`TQL-SQL-2101` carries no source line and no expression text.**

## What the plan got wrong

Recorded because the plan was measured by many agents against the audit commit and was still wrong
in eleven places, four of which would have produced a red build or a broken document.

- **Its first slice had already shipped.** F17 landed as #1148 the same day the audit was written.
- **`## Unreleased` already exists**, created by #1148. Following the plan literally adds a second
  one. The cross-cutting "nominate one owner" instruction is dead.
- **`TQL-SQL-2001` exists.** The reviewer's instruction to "fix the cited code" in
  `docs/two-way-sql.md` would have broken a correct sentence and a correct generated row, because
  the reference cites that exact page.
- **`StatusMappingLedgerTest` is no longer 4xxx-only.** It covers the LD domain too, since #1127 —
  two days before the plan was written. The mechanical conclusion for `TQL-SQL-2118` survives; the
  argument for it does not.
- **The regen prerequisite is `compile`, not `install`.** Three of the four reference pages are
  generated from a repository *source* scan, so a stale `.m2` cannot regenerate an old table. What
  can happen, and has, is a regen running stale generator classes: `exec-maven-plugin`'s `java`
  mojo has `phase=NONE` and compiles nothing.
- **Slice 3's dependency on slice 2 does not exist**, so the campaign's critical path was a slice
  longer than it needed to be.
- **F24's justification was falsified by the plan's own example**, and two of its five proposed
  tests do not test what it says.
- **F19's headline was wrong.** A marked path parameter does not bind null silently — the runtime
  fails to boot. The genuinely silent case is different: an ASCII-prefixed decomposed name mounts
  and Vert.x truncates the parameter to its prefix.
- **F19's "exactly `isJavaIdentifierPart` minus the invisibles" is off by 987 code points** in both
  directions.
- **F20's recommended guard idiom is fail-open on F20's own headline scenario.**
- **F18's inventory was wrong in four of seven entries**, and the real count is fourteen sites, not
  thirteen.
