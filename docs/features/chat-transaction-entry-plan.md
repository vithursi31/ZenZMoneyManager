# Plan — Chat-Based Transaction Entry (NLP capture via Qwen2.5)

**Status:** Implemented — `ChatService`, `IntentResolver`, `OllamaExtractionClient`,
`ChatController` and their tests are in the tree. This document is retained as the
design rationale; where it disagrees with the code, the code wins.

**Feature IDs:** F-1.11 (chat entry), F-1.14 (auto-category suggestions).
*Renumbered 2026-08-08 from F-1.9a / F-1.9b — see the
[ID mapping](../features-list.md#id-mapping-2026-08-08).*
**Domain basis:** [domain §3.1 capture pipeline](../domain/domain-documentation.md),
§3.3 `ParsedIntent`, §3.4 `ChatMessage`, and Part 1 `Transaction`/`Category`.

---

## 1. Goal

Let a user type a natural-language message — e.g. **"I have spent $5 for burger"** —
and have the backend:

1. Send the message to a **self-hosted Qwen2.5** model.
2. Get back a **structured interpretation** (a `ParsedIntent`): type = EXPENSE,
   amount = `500` minor units, category guess = "food", date = today.
3. Resolve the guess to one of the **user's own categories** (Food & Drinks) and
   resolve the date.
4. Return a **draft** the user confirms.
5. On confirm, write a real `Transaction` to the ledger (Part 1) — reusing the
   existing transaction rules. The account is the user's single account, resolved
   server-side (domain §1.4).

**Worked example A — item, no merchant**

```
User:      "I have spent $5 for burger"
           │
           ▼  POST /api/v1/chat
Qwen2.5 → { intent: CREATE_TRANSACTION, txnType: EXPENSE,
            amount: 5.0, categoryGuess: "food", dateExpr: "today",
            payee: null, note: "burger", confidence: 0.93 }
           │
           ▼  backend normalizes (amount→minor, guess→categoryId,
           │                       dateExpr→epoch millis in user TZ)
Draft:     EXPENSE · $5.00 · Food & Drinks · today · payee=null · note="burger"
           │
           ▼  POST /api/v1/chat/confirm { messageId }
Ledger:    Transaction row created; that month's position now reflects it.
Assistant: "Added $5.00 expense in Food & Drinks for today."
```

**Worked example B — merchant + item detail**

```
User:      "I spent $15 in the Keells supermarket for grocery (tea things)"
           │
           ▼
Qwen2.5 → { intent: CREATE_TRANSACTION, txnType: EXPENSE,
            amount: 15.0, categoryGuess: "grocery", dateExpr: "today",
            payee: "Keells", note: "tea things", confidence: 0.93 }
           │
           ▼  backend normalizes
Draft:     EXPENSE · $15.00 · Groceries · today · payee="Keells" · note="tea things"
```

**payee vs note (the rule the prompt enforces):**
- `payee` = the **merchant or person** named (a proper name/business): "Keells",
  "McDonald's", "Uber", "John". If none is named, `payee = null`.
- `note` = the **item or description** of what the money was for: "burger",
  "tea things". Never put a generic item in `payee`.
- Example A has no merchant → `payee: null`, item → `note: "burger"`.
- Example B names a merchant → `payee: "Keells"`; the parenthetical detail →
  `note: "tea things"`.

---

## 2. Scope

**In scope (this plan)**
- `CREATE_TRANSACTION` intent (income + expense) from typed chat.
- Auto-category detection against the user's categories (F-1.14).
- Relative-date resolution ("today", "yesterday") in the user's timezone.
- Two-step **parse → confirm** flow with a hard write gate (domain §3.1).
- `ChatMessage` logging (user + assistant turns), `ParsedIntent` persisted inline.
- Self-hosted **Qwen2.5 via Ollama**, called over an OpenAI-compatible HTTP API.

**Out of scope (later phases / separate plans)**
- `QUERY` intent / financial assistant (F-1.16) — read-side, deterministic aggregates.
- `UPDATE_TRANSACTION` — needs session-target resolution; add after create works.
- Voice entry (F-1.12) — reuses this pipeline behind speech-to-text. Now MVP, not Phase 2.
- OCR / receipts (F-1.13) — same draft→confirm funnel, different extractor (§3.5).

> **`TRANSFER` is gone, not deferred.** The single-account model removed the
> transaction type entirely (domain §1.6); a transfer needs two accounts. It
> returns only with F-F.1.

---

## 3. Model serving — Qwen2.5 via Ollama

### 3.1 Why Ollama
- Single-VM friendly (matches the current Oracle Cloud deploy target).
- Ships an **OpenAI-compatible** `/api/chat` with a **JSON `format`** mode, so we
  can force structured output without prompt-only coaxing.
- Model swap is a config change (`qwen2.5:7b-instruct` → `qwen2.5:3b-instruct`
  for a smaller VM), no code change.

### 3.2 Deployment
- Add an `ollama` service to `docker-compose.prod.yml` on the internal `zenz-net`
  network (not publicly exposed), with a named volume for pulled models.
- One-time model pull on the host: `ollama pull qwen2.5:7b-instruct`
  (choose 3B for CPU-only / small RAM; 7B if a GPU or ≥16 GB RAM is available).
- App reaches it by service name: `http://ollama:11434`.

### 3.3 Model choice guidance
| VM profile | Model tag | Notes |
|---|---|---|
| CPU-only, ≤8 GB | `qwen2.5:3b-instruct` | Fastest; acceptable for short extraction prompts. |
| CPU/GPU, 16 GB | `qwen2.5:7b-instruct` | Better category/date reasoning. Recommended default. |
| GPU, 24 GB+ | `qwen2.5:14b-instruct` | Best accuracy; optional. |

> The extraction task is small and well-bounded, so a 3B/7B instruct model is
> sufficient — this is entity extraction, not open-ended generation.

---

## 4. Architecture & flow

```
                       ┌──────────────────────────────────────────────┐
POST /api/v1/chat ───► │ ChatService.handle(userId, message)          │
                       │  1. save ChatMessage(USER, RECEIVED)          │
                       │  2. LlmExtractionClient.extract(msg, ctx) ────┼──► Ollama (Qwen2.5)
                       │  3. IntentResolver: guess→categoryId,         │      JSON out
                       │       date→millis, the one account, $→minor   │
                       │  4. confidence/missing check                  │
                       │  5. save ParsedIntent + ASSISTANT reply       │
                       └───────────────┬──────────────────────────────┘
                                       ▼
                     confident?  ── no ──► status = NEEDS_CLARIFICATION
                                           (assistant asks a question)
                                       │ yes
                                       ▼
                        DRAFT returned (status = PARSED); NO ledger write

POST /api/v1/chat/confirm { messageId } ─► TransactionService.create(fromIntent)
                                           status = CONFIRMED, transactionId set;
                                           the month's position now includes it (§1.10)
```

**Key principle (domain §3.7):** the model *proposes*; the user *commits*. No AI
path writes to the ledger without the explicit confirm call.

---

## 5. New components

All under `com.zenzmoney.core`, mirroring existing service/connector style
(constructor injection, `WebClient` like the OAuth connectors, `@Service`).

### 5.1 Enums (`common/domain`)
- `IntentType { CREATE_TRANSACTION, UPDATE_TRANSACTION, QUERY, UNKNOWN }`
- `ChatRole { USER, ASSISTANT }`
- `ChatMessageStatus { RECEIVED, PARSED, NEEDS_CLARIFICATION, CONFIRMED, REJECTED, FAILED }`

### 5.2 Entity + repository
- `ChatMessage` entity (`chat_message` table) — per domain §3.4: `userId`, `role`,
  `content`, `language`, `parsedIntent` (jsonb), `status`, `transactionId`,
  `sessionId`. Extends `BaseEntity`.
- `ParsedIntent` — **not a table**; a plain class serialized to the `parsedIntent`
  jsonb column (`@JdbcTypeCode(SqlTypes.JSON)`), fields per §3.3.
- `ChatMessageRepository` — `findByUserIdAndSessionId(...)`, `findByIdAndUserId(...)`.

### 5.3 LLM client
- `LlmExtractionClient` (interface) + `OllamaExtractionClient` (impl).
  - `WebClient` POST to `${zenzmoney.llm.base-url}/api/chat`.
  - Request: system prompt (extraction instructions + the user's category names) +
    user message; `format: "json"` (or a JSON schema) to force structured output;
    `stream: false`; low temperature (~0.1) for determinism.
  - Response: parse the model's JSON into a raw `LlmExtraction` DTO
    (txnType, amount, categoryGuess, dateExpr, payee, note, confidence).
  - Timeout + fail-safe: on timeout/parse failure → `intent = UNKNOWN`,
    `status = FAILED`, assistant asks the user to rephrase. Never throws to the user.

### 5.4 Normalization
- `IntentResolver` (service) turns the raw `LlmExtraction` into a domain
  `ParsedIntent`:
  - **Amount → minor units** using the user's active currency (§0.2). `$5` → `500`.
    Currency is **never** taken from the text (§3.3 rule) — always the user's
    `active_currency`.
  - **Category matching (F-1.14):** resolve `categoryGuess` against the user's
    `Category` rows — case-insensitive, kind-aware (EXPENSE guess → EXPENSE
    categories), with a small synonym map ("burger/lunch/coffee" → Food & Drinks).
    Null if no confident match.
  - **Date resolution (backend, not model):** the model emits only a phrase
    (`dateExpr`); the resolver converts "today"/"yesterday"/"last Friday" → epoch
    millis against the **server clock + user's `timezone`** (reuse `TimeUtils`).
    "today" = the user's local day. Default = now. The model never outputs an
    absolute date.
  - **payee (name) / note pass-through:** `note` is carried as-is (trimmed). The
    model's `payee` is kept as a **name string** (`ParsedIntent.payeeName`) at the
    draft stage — it is **not** resolved to a `payee_id` yet, so the user can edit
    it before any `Payee` row is created. Resolution to `payeeId` happens only at
    confirm, via `PayeeService.resolveOrCreate` (§5.7). The prompt already enforces
    payee=merchant/person-or-null and note=item/description.
  - **Account:** the user's single account (domain §1.4) — never chosen, never sent by the client.
  - **missingFields / confidence:** if amount or category can't be filled, or
    confidence < threshold, populate `missingFields` so the flow branches to
    clarification.

### 5.5 Orchestration
- `ChatService.handle(userId, message, sessionId)` — the pipeline in §4.
- `ChatService.confirm(userId, messageId)` — validates the draft, resolves the
  draft's `payeeName` to a `payeeId` via `PayeeService.resolveOrCreate` (§5.7),
  calls the existing `TransactionService` (Core-Ledger service layer) to create the
  row with that `payeeId`, sets `status = CONFIRMED` + `transactionId`.
- `ChatService.reject(userId, messageId)` — `status = REJECTED`, no write.

### 5.6 Controller + DTOs
- `ChatController` (`/api/v1/chat`):
  - `POST /api/v1/chat` `{ message, sessionId? }` → `ChatReplyResponse`
    (assistant text + draft `ParsedIntent` + status).
  - `POST /api/v1/chat/confirm` `{ messageId }` → the created transaction (or draft echo).
  - `POST /api/v1/chat/reject` `{ messageId }` → ack.
  - `GET  /api/v1/chat?sessionId=` → conversation history (optional, for replay).
- All authenticated (JWT filter already covers `/api/v1/**` non-public paths).
- Wrapped in the existing `ApiResponse<T>` envelope.

### 5.7 Payee entity (domain amendment — replaces the free-text `payee` string)

**Why.** The domain doc currently models `payee` as `String(300)` on `Transaction`
and `RecurringTransaction` (§1.6 / §1.8), and F-1.9 lists "filter by payee" as a
first-class MVP capability. A free-text string makes that filtering weak:
"Keells" / "keells" / "Keells Super" are distinct values — no autocomplete, no
dedup, no "total spent at Keells" aggregation. We promote payee to a **user-owned
entity** with a FK from the transaction, mirroring how `Category` works.

> **This is a proposed change to the domain doc** (§1.6, §1.8) and to the already-
> built `Transaction` / `RecurringTransaction` entities + the `V2` finance
> migration. It must be verified before implementation. See §7 and §13.

**`Payee` entity** (`payee` table) — extends `BaseEntity`, user-owned like `Category`:

| Field | Type | Notes |
|---|---|---|
| `id` | `String` (UUID) | PK, from `BaseEntity`. |
| `userId` | `String` | Owner. Not null, indexed. |
| `name` | `String(300)` | Display name as first entered, e.g. "Keells". Not null. |
| `normalizedName` | `String(300)` | Lower-cased/trimmed key for matching + uniqueness. Not null, indexed. |
| `color` / `icon` | `String` | Optional UI hints (future; parity with Category). |

- **Uniqueness:** one payee per (`userId`, `normalizedName`) — a partial unique
  index. Resolving "Keells" and "keells" collapses to the same row.
- **Ownership/scoping:** every query scoped by `user_id` (§1.12), same as all owned
  entities. No cross-user payees.

**FK on the transaction (replace the string):**
- `Transaction.payeeId` (`payee_id VARCHAR(36)`, nullable, indexed, FK → `payee`)
  **replaces** the `payee VARCHAR(300)` column.
- `RecurringTransaction.payeeId` likewise; generated transactions copy `payeeId`.
- Nullable because unnamed one-off expenses ("$5 for burger") have no payee.

**Repository:** `PayeeRepository` — `findByUserIdAndNormalizedName(...)`,
`findByUserId(...)` (autocomplete / "top payees"), `findByIdAndUserId(...)`.

**Resolve-or-create service** (`PayeeService`):
- `resolveOrCreate(userId, rawName) -> payeeId`:
  1. normalize `rawName` (trim, collapse spaces, lower-case).
  2. `findByUserIdAndNormalizedName` → return its id if found.
  3. else create a new `Payee(userId, name=rawName, normalizedName=…)` and return.
- Used by **every** transaction-writing path: the chat confirm flow, OCR, and the
  manual create/edit transaction endpoints — so payee resolution is centralized.
- Merge (F-2.4-style "merge Keells Super into Keells") is a later addition; the
  entity makes it possible.

**How the chat flow uses it:** the model still returns `payee` as a **name string**
("Keells"); the backend `IntentResolver` keeps it as `payeeName` on the draft
`ParsedIntent`. Only at **confirm** does `ChatService` call
`PayeeService.resolveOrCreate` and set `Transaction.payeeId`. (Draft stays
name-only so the user can edit before a payee row is created.)

**Filtering (F-1.9):** transactions filter/group by `payee_id` — enabling
"total spent at Keells", payee autocomplete, and a payees list, none of which a
free-text column supports.

---

## 6. The extraction prompt (contract with Qwen2.5)

**System prompt (sketch):**
> You extract a single personal-finance transaction from the user's message.
> Return ONLY JSON matching this schema. Amount is a number in major units.
> Do not infer currency.
> - `payee` = the merchant or person named (e.g. "Keells", "Uber"). If no
>   merchant/person is named, set `payee` to null.
> - `note` = the item or description of what the money was for (e.g. "burger",
>   "tea things"). Never put a generic item in `payee`.
> - `dateExpr` = the date **as the user phrased it** ("today", "yesterday",
>   "last Friday"). **Do not compute or output an absolute date/time** — you do
>   not know the current date. The backend resolves it.
> Pick the best category label from this list: {user's category names}. If
> unsure, set confidence low and leave fields null.

**Forced JSON output shape (raw, pre-normalization):**
```json
{
  "intent": "CREATE_TRANSACTION | UPDATE_TRANSACTION | QUERY | UNKNOWN",
  "txnType": "INCOME | EXPENSE | null",
  "amount": 15.0,
  "categoryGuess": "grocery",
  "dateExpr": "today",
  "payee": "Keells",
  "note": "tea things",
  "confidence": 0.93
}
```

**Model layer vs backend layer — strict split.** The backend — **not the model** —
converts amount→minor units, guess→categoryId, dateExpr→epoch millis, and applies
the user's currency and account. The model never sees or sets IDs, minor units, or
timestamps. This keeps money math and dates deterministic and auditable.

**Date resolution (why the model only emits a phrase):**
- An LLM has no clock and is error-prone at date arithmetic, so it must **not**
  output an absolute timestamp — it emits the phrase only (`dateExpr: "today"`).
- The backend `IntentResolver` resolves `dateExpr` against **the server clock +
  the user's `app_user.timezone`** to an exact instant, stored as epoch-millis
  (domain §0.1 / §3.3). "today" means the user's **local** day, not the UTC day —
  correct for non-UTC users (e.g. Asia/Colombo, UTC+5:30).
- The resolved `ParsedIntent.txnDate` therefore carries the exact moment (e.g.
  `2026-07-25 11:00` in the user's TZ → its epoch-millis value); only the *model's*
  raw output stays as the phrase.

---

## 7. Data & migration

**`V3__chat_ingestion.sql`** — the chat side:
- `chat_message` table (`user_id` indexed, `session_id` indexed,
  `parsed_intent jsonb`, `transaction_id` FK-nullable, `status`, `role`,
  `language`).
- `ParsedIntent` is embedded jsonb on `chat_message`; no separate table (§3.3).

**`V3` also carries the Payee change** (§5.7) — since payee touches the ledger
schema, it ships in the same migration (or a dedicated `V3b` if you prefer to keep
chat and ledger changes separate):
- `CREATE TABLE payee (id, user_id, name, normalized_name, color, icon, + audit
  columns)`; index on `user_id`; **unique index on (`user_id`, `normalized_name`)**.
- `ALTER TABLE transaction`: **drop `payee VARCHAR(300)`**, add
  `payee_id VARCHAR(36)` (nullable, indexed, FK → `payee`).
- `ALTER TABLE recurring_transaction`: same swap.

> Because the app is **not yet deployed** and the DB is recreated on each run
> (per earlier setup), this can instead be folded **directly into the existing
> `V2__finance_schema.sql`** — `payee` table created there, and
> `transaction`/`recurring_transaction` use `payee_id` from the start (no
> drop-column needed). **Recommended** while pre-deploy: cleaner history. Decide in
> §13. Either way, the already-built `Transaction`/`RecurringTransaction` entities
> must change from `String payee` → `String payeeId`.

---

## 8. Config (application properties / env)

```
zenzmoney.llm.provider=ollama
zenzmoney.llm.base-url=${LLM_BASE_URL:http://ollama:11434}
zenzmoney.llm.model=${LLM_MODEL:qwen2.5:7b-instruct}
zenzmoney.llm.timeout-ms=${LLM_TIMEOUT_MS:20000}
zenzmoney.llm.temperature=0.1
zenzmoney.chat.confidence-threshold=0.7
```
- Same env-var-driven pattern as the existing OAuth/JWT config.
- `docker-compose.prod.yml` gains the `ollama` service + these env vars on `app`.

---

## 9. Error handling & safeguards

| Case | Behavior |
|---|---|
| Model timeout / down | `status = FAILED`; assistant: "I couldn't read that, try again." No 5xx to client. |
| Model returns non-JSON | Same as above; log raw output at DEBUG. |
| Low confidence / missing amount | `status = NEEDS_CLARIFICATION`; assistant asks one targeted question. |
| Category unresolved | Draft still returned with `categoryId = null`; user picks/creates on confirm. |
| Confirm on already-confirmed msg | Reject with a clear error; idempotent (no double-write). |
| Rate limiting | Reuse the existing Redis limiter (per-user cap on `/chat`) to bound model calls. |

**Privacy (§3.7):** only the user's own message + their category *names* go to the
model. No cross-user data; no amounts/IDs from other users. The model runs
self-hosted, so data never leaves the deployment.

---

## 10. Testing plan

- **Unit:** `IntentResolver` — amount→minor units per currency; date expressions;
  category matching incl. synonyms and no-match; confidence branching.
- **Contract:** `OllamaExtractionClient` against a mocked `/api/chat` (WireMock-style)
  — valid JSON, malformed JSON, timeout.
- **Integration:** `POST /chat` → draft → `POST /chat/confirm` → row exists and
  counts toward its month; reject path writes nothing.
- **Prompt eval (manual/offline):** a fixture set of ~30 phrasings
  ("spent 5 on burger", "got salary 3000", "paid 12 for uber yesterday") checked
  against expected extractions before shipping.

---

## 11. Build order (once approved)

**Payee change (prerequisite — touches the ledger schema):**
0. `Payee` entity + `PayeeRepository` + `PayeeService.resolveOrCreate`; swap
   `Transaction`/`RecurringTransaction` `String payee` → `String payeeId`; fold the
   `payee` table + `payee_id` columns into `V2` (pre-deploy) or add `V3`. Update the
   domain doc §1.6/§1.8 and feature list (done alongside this plan).

**Chat feature:**
1. Enums + `ChatMessage` entity + `ParsedIntent` (with `payeeName`) + repository +
   `V3` chat migration.
2. `LlmExtractionClient` / `OllamaExtractionClient` + config + compose `ollama` service.
3. `IntentResolver` (normalization) + unit tests.
4. `ChatService` (handle/confirm/reject): confirm resolves `payeeName` →
   `payeeId` via `PayeeService`, then calls the Core-Ledger `TransactionService`.
5. `ChatController` + DTOs.
6. Integration tests + offline prompt eval.

> **Dependencies:** step 0 (Payee) is independent and can land first. Step 4 needs
> the Core-Ledger **service** layer (Transaction create), which is now in place.
> The chat entity/LLM/resolver layers
> (1–3) can be built in parallel; confirm-write (4) lands after both
> `TransactionService` and `PayeeService` exist.

---

## 12. Open questions for review

1. **Model size** — 3B (CPU, small VM) vs 7B (better accuracy)? Depends on the
   target VM's RAM/GPU.
2. **Category auto-create** — if the guess matches no category and confidence is
   high, offer to create it (§3.3), or always require an existing category for MVP?
3. **Confidence threshold** — start at 0.7? Tune against the prompt-eval fixtures.
4. **Session model** — one rolling `sessionId` per user, or a new session per
   "conversation"? Affects `UPDATE_TRANSACTION` later.
5. **Language** — MVP English-only extraction, or wire Qwen2.5's multilingual
   ability (ta/si) now? Model supports it; prompt + category synonyms would need
   localization.

---

## 13. Payee change — decisions & remaining choices

**Decided (this review):**
- Payee becomes a **user-owned entity** (`payee` table), like `Category`.
- `Transaction.payee_id` FK **replaces** the free-text `payee` string (not kept as
  a fallback). Same for `RecurringTransaction`.
- Payees are **auto-created on first use** via `PayeeService.resolveOrCreate`,
  deduped by (`userId`, `normalizedName`).

**Still to confirm:**
1. **Migration placement** — fold into `V2__finance_schema.sql` (recommended
   pre-deploy, clean) vs a new `V3`/`V3b` alter. (§7)
2. **Normalization rule** — lower-case + trim + collapse-whitespace only, or also
   strip punctuation / common suffixes ("Super", "Pvt Ltd")? Stricter = more
   dedup but risks false merges.
3. **Domain doc** — §1.6 and §1.8 payee rows change from `String(300)` to a
   `payeeId` FK; ERD updated. (Being updated alongside this plan.)```
