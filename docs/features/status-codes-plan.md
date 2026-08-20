# Status codes — remaining work

**Shipped 2026-08-20.** `StatusCode` + the two registries (`StatusCodes`, `ServiceCodes`), the
exception hierarchy rooted at `ServiceException`, and the whole auth surface converted off ad-hoc
name codes. The conventions, the band map and the standing rule are in
[CLAUDE.md](../../CLAUDE.md); the client-facing catalogue is in
[mobile-api-guide.md](../mobile-api-guide.md). This file only records what is **not** done.

## What is left: the `E11xx` domain band

Every business-rule rejection in the ledger still answers `E1013` (`SC_BAD_REQUEST`) with a distinct
message — 70 throw sites. That was left alone on purpose: minting 70 codes nobody branches on is
churn, and a code that no client reads is a contract you have to keep for nothing.

**Promote a rejection to its own code when a client would act differently on it** — a different
screen, a different retry, a different message it needs to localise. Not merely because it is a
different sentence.

The first candidates, in the order they are worth doing (one feature area per commit, each with the
guide's endpoint section updated):

| Band | Rejection | Why a client cares |
|---|---|---|
| `E111x` | Deleting the caller's last `ACTIVE` account | The UI should disable the control, not surface a generic 400 |
| `E111x` | Account already deleted | Idempotent-delete clients want to treat this as success |
| `E115x` | An active budget already exists for that account + category + period | The client can offer "edit the existing budget" instead |
| `E115x` | `periodKey` doesn't match the period type | A field-level error, not a form-level one |
| `E114x` | Category kind doesn't match the transaction type | Should highlight the category picker |
| `E110x` | No active currency set yet | The client must route into onboarding, and today has to string-match to know that |
| `E110x` | Onboarding currency change attempted | Distinct remedy: contact support, not "try again" |

## Rules for that work

- One code, one meaning, and the band decides the number — see the band map in `CLAUDE.md`.
- Every promotion is a **wire change**: add the row to the catalogue *and* to the endpoint's own
  error list in the guide, in the same commit.
- `E1013` stays the answer for everything not promoted. A client must keep handling it.
- `ServiceCodesTest` guards uniqueness and format; a new code needs no new test of its own, but the
  rejection it labels needs one asserting the code (not just the status).

## Also unfinished

- **`E1301` (SMTP) and `E1302` (LLM) are unassigned.** `SmtpEmailSender` and the Ollama clients
  swallow their failures today rather than throwing, so there is nothing to label. When a send
  failure or a model timeout becomes a client-visible outcome, those are its codes.
- **`E1303` is reserved for FCM**, for F-1.20 notifications.
- **A 405 answers `E1013`.** The framework's status is kept and the code is the generic bad-request
  one, so status and code disagree slightly. Harmless today; a `SC_METHOD_NOT_ALLOWED` would fix it
  if anything ever branches on it.
