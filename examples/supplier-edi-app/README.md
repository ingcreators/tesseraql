# supplier-edi-app

The supplier's side of the procurement demo's EDI exchange (docs/procurement-documents-and-edi.md):
the buyer's `procurement-app` pushes a receipt notice — the delivery notes it received on a
business date, as CSV — to an SFTP drop, and this application **polls** that drop, imports
every row through one upsert, and lists what arrived. Two applications, two databases, one
file crossing between them under a host key each side pins.

What it exercises:

- **A `poll:` trigger on a `file-import` job** (`batch/edi/receipt-notices`): the SFTP drop
  is listed under the poll policy block in `config/tesseraql.yml` — an allow-listed host, a
  named credential, a pinned host key (`security/known_hosts`), `consumeOnce` so a file is
  imported by one replica and a re-sent identical file is skipped.
- **A per-row upsert** keyed by the delivery note's number, so a rerun on the buyer's side —
  a new upload, which the consume-once claim reads as a new file — updates rather than
  duplicates.
- **A declarative list** at `/notices` and its JSON twin at `/api/notices`, behind a
  `notices.read` policy.
- **The operations console** shows the poll source's status (polling, or why it is not) and
  every imported file as a transfer with its row outcomes.

Run it like any gallery app:

```bash
tesseraql dev --app-name supplier-edi --embedded-db
```

## The crossing

The tour (`../procurement-app/README.md`, step 7) runs both applications from the examples
stack and one SFTP drop between them:

```bash
docker run --rm -d --name edi-drop -p 2222:22 atmoz/sftp edi:edi-secret:::drop
ssh-keyscan -p 2222 localhost >> ../procurement-app/security/known_hosts
ssh-keyscan -p 2222 localhost >> security/known_hosts
cd .. && tesseraql dev --embedded-db      # every example, /procurement/… and /supplier-edi/…
```

Until the keys are pinned the buyer's push fails its job and this side's poll refuses the
server every cycle — the poll source's status on `/supplier-edi/_tesseraql/ops` says why.
Once the buyer runs `edi.receiptNotice`, the file lands whole, the next cycle (every 30
seconds) imports it, and `/supplier-edi/notices` lists the delivery notes. Mint a reader:

```bash
tesseraql token --app . --sub kita --role SUPPLIER
```

## Test it

```bash
tesseraql test --app .
```
