# Features List — ZenZ Money Manager

This document is the product-level feature catalogue for ZenZ Money Manager. It
groups every planned capability by **implementation phase** and gives each a
short description. It is the companion to the domain documentation and the
[roadmap](roadmap.md).

**Source of truth:** this list tracks the *Business Requirements & Feature
Specification v1.0 (8 August 2026)*. Feature IDs and phase placement follow that
document; the **Status** column is this repo's own — it records what is actually
built, which the BRD does not track.

**Domain doc:** the full domain model lives in a single consolidated document,
[domain-documentation.md](domain/domain-documentation.md).

> **How to read this doc.** Each feature has a stable ID (e.g. `F-1.3`) referenced
> from the domain docs, tickets, commits, and tests. IDs group by phase (`F-1.x` =
> MVP, `F-2.x` = Phase 2, `F-3.x` = Phase 3, `F-F.x` = future). **The IDs were
> renumbered on 2026-08-08** to match the BRD — see the [mapping table](#id-mapping-2026-08-08)
> before reading an older commit message or branch name.

**Legend:** ✅ Done · 🚧 In progress · 📋 Planned · 💡 Proposed (not committed) · 🚫 Out of scope

> **"Done" means the backend.** There is no UI yet — the Thymeleaf templates are
> placeholders. A feature marked 🚧 has working services and REST endpoints and is
> waiting on a client.

---

## Out of scope

Deliberately excluded from the committed phases. Recorded here so the exclusion is
a decision rather than an oversight — **do not build these**.

| Excluded | Why |
|---|---|
| A stored or opening account balance | The position is derived from the month's transactions, not stored. There is no starting balance to enter and none to carry forward (F-1.2). |
| Balance reconciliation against a real bank balance | The position is derived from what the user records, so there is no independent figure to reconcile against. |
| Assigning a transaction to a specific account | A user may hold multiple accounts (F-1.1), but the ledger-write path still resolves one implicit account server-side — the account is still never chosen by the user. Remaining future work: F-F.1. |
| Transfers between accounts | Requires assigning a transaction to a specific account first. Remaining future work: F-F.1. |
| Active session management (device list, remote sign-out) | Not required for the MVP; access is protected by App Lock (F-1.23). |
| Attaching receipt or image files to a transaction | Receipt scanning (F-1.13) extracts the data that matters; storing the image is not required. |
| Multiple currencies per user | One active currency per user (F-1.25). Future: F-F.2. |

---

## Phase 1 — MVP

A complete personal finance experience built around **one or more accounts**, on a
**monthly cycle**: see this month's position, track income and expenses, budget,
manage recurring commitments, capture transactions by typing / speaking /
scanning, and get insight — in the user's language and currency, behind a lock.

### 1. Account & monthly position

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-1.1** | Accounts | An account is a container for the user's financial activity; one is created automatically at onboarding, and the user may add, rename, list, and soft-delete more (F-F.1's account-CRUD slice, pulled into Phase 1 on 2026-08-18) — a user must always keep at least one active. No starting balance is requested, and the user is still never asked which account a transaction belongs to — that resolves to one implicit account server-side. See [domain-documentation.md §1.4](domain/domain-documentation.md#14-account). | 🚧 |
| **F-1.2** | Monthly position (income − expenses) | The account has **no stored balance**. The figure shown is computed as *total income − total expenses for the selected calendar month*. Nothing carries forward across months, nothing accumulates, and there is no reset. Any past month can be selected and its position computed. Adding / editing / deleting a transaction recalculates **only** the month that transaction falls in. See [domain-documentation.md §1.10](domain/domain-documentation.md#110-monthly-position-invariant). | 🚧 |

### 2. Income & expense management

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-1.3** | Expense transactions | Record an expense with amount, category, date, payee/merchant, and notes. | 🚧 |
| **F-1.4** | Income transactions | Record income independently of expenses, with dedicated income categories (Salary, Business, Freelance, Investments, Gifts). Income is a first-class type and is reflected separately in summaries and reports. | 🚧 |
| **F-1.5** | Categories & sub-categories | Create and edit categories with one level of sub-categories, icons, and colors. Income and expense categories are organised separately. New users start with a seeded default set. See [domain-documentation.md §1.5](domain/domain-documentation.md#15-category). | 🚧 |
| **F-1.6** | Payees / merchants | Payees are a first-class, user-owned entity (not free text): remembered on first use, deduped by normalized name, with autocomplete and "total spent at X" reporting. Shared by manual entry, chat (F-1.11), voice (F-1.12), and scanning (F-1.13). See [domain-documentation.md §1.5b](domain/domain-documentation.md#15b-payee). | 🚧 |
| **F-1.7** | Recurring income, expenses & subscriptions | One model for everything that repeats — salary, rent, utilities, and subscriptions (streaming, music, gym): amount/cost, repeat or billing frequency, next due or renewal date, and an optional **free-trial end date**. Generates transactions into the month they fall in, consistent with F-1.2, and drives the reminders in F-1.20. See [domain-documentation.md §1.8](domain/domain-documentation.md#18-recurringtransaction). | 🚧 |
| **F-1.8** | Transaction editing & history | Edit and delete transactions, annotate them with notes/comments, and review history. A change recalculates the affected month's position and flows into reports. | 🚧 |
| **F-1.9** | Search & filter transactions | Find transactions by keyword, date range, category, amount, payee, and tags. | 📋 |

### 3. Budgeting

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-1.10** | Budgets | Spending caps per account, per category and overall, for a monthly or yearly calendar period, showing how much of the budget is used. Unused amounts may optionally carry forward to the next period (see [OQ-3](#open-questions)). | 🚧 |

### 4. Quick entry & intelligent assistance

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-1.11** | Chat-based transaction entry | Record income and expenses in plain language — *"I spent 15 at Keells for tea"*, *"I received my salary of 3000"*. A **self-hosted model (Qwen2.5 via Ollama)** extracts intent / type / amount / category-guess / date-phrase / payee / note; the **backend** resolves each field — amount → minor units in the active currency, guess → a real [Category](domain/domain-documentation.md#15-category), date-phrase → an exact timestamp in the user's timezone, payee-name → a [Payee](domain/domain-documentation.md#15b-payee) row. The flow is **two-step**: the message returns a **draft** and nothing is written to the ledger until the user **confirms**. Missing or unclear information produces a **clarifying question** rather than a guess. Conversations are logged (`ChatMessage`) so a saved transaction traces back to what was typed. See [domain-documentation.md Part 3](domain/domain-documentation.md#part-3--ingestion--ai-mvp) and the [implementation plan](features/chat-transaction-entry-plan.md). | 🚧 |
| **F-1.12** | Voice-based expense entry | Speak a transaction — *"I spent 2,500 rupees on groceries"*. Speech is transcribed and passed through the same pipeline as F-1.11, ending in the same review-and-confirm step. | 📋 |
| **F-1.13** | Bill & receipt scanning | Photograph or upload a receipt/bill; the system extracts **merchant, date, and total** and pre-fills an expense for confirmation. The image itself is not stored (see [out of scope](#out-of-scope)). | 📋 |
| **F-1.14** | Automatic category suggestions | Suggest a category from the merchant and wording — a coffee-shop purchase → *Food & Drinks → Coffee*. The user can change it, and corrections improve later suggestions. | 📋 |
| **F-1.15** | Financial insights | Insights derived from the user's activity: spending trends, unusual spending, budget risk, saving opportunities, and changes in behaviour. | 📋 |
| **F-1.16** | Financial assistant | Ask questions in plain language — *"How much did I spend on food last month?"*, *"What category did I spend the most on?"* — answered from the user's own recorded data. | 📋 |

### 5. Dashboard & reports

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-1.17** | Financial summary (dashboard) | Total income, total expenses, and the resulting position **for the selected month**, consistent with F-1.2. | 🚧 |
| **F-1.18** | Spending analysis | Monthly spending trend, highest-spending categories, and this-month-vs-previous-month comparison. | 📋 |
| **F-1.19** | Basic financial reports | Income vs. expenses, spending by category, and budget progress — exportable via F-1.21. | 📋 |

### 6. Notifications & reminders

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-1.20** | Notifications & reminders | Approaching budget limits, upcoming bill payments and recurring expenses, and subscription renewals / free-trial expirations. Delivered as **FCM push** to the user's registered devices — the app is usually closed when these fire — with the REST API remaining the source of truth. See [push-notifications-fcm-plan.md](features/push-notifications-fcm-plan.md). | 📋 |

### 7. Data ownership

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-1.21** | Data export | Export financial information as CSV, Excel, or PDF. | 📋 |
| **F-1.22** | Data import | Import transactions from bank-provided CSV or Excel files, with user-controlled column mapping. | 📋 |

### 8. Security

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-1.23** | App lock | Protect access with a PIN, fingerprint, or face authentication, with a configurable auto-lock delay. See [domain-documentation.md Part 4](domain/domain-documentation.md#part-4--security-mvp). | 📋 |
| **F-1.24** | Data protection | Financial information protected in transit (TLS) and at rest. | 📋 |

### 9. Platform & user experience

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-1.25** | Active currency | The user selects the currency they manage money in; it is used consistently across the account, transactions, budgets, and reports, and can be changed. One active currency at a time — mixing is not supported (F-F.2). See [domain-documentation.md §0.3](domain/domain-documentation.md#03-one-active-currency-per-user). | 🚧 |
| **F-1.26** | Multi-language support | The user picks a preferred language, honoured across the application **and** the intelligent-assistance features. **Server-side messages are done** — every API error message, bean-validation reason and OTP email renders in the caller's language (12 bundles ship: `en`, `zh-CN`, `zh-TW`, `fr`, `es`, `pt`, `de`, `it`, `ru`, `ja`, `ko`, `si`), chosen from the stored preference first and `Accept-Language` second; `errorCode` is unchanged. **Still open:** (a) every bundle except English is machine-assisted and **not reviewed by a native speaker** — each file says so in its header, and this is what blocks calling it done for real users; (b) chat/assistant replies, the LLM prompt language and the Thymeleaf pages are still English by choice — the same key/bundle machinery serves them unchanged when they are picked up; (c) Tamil (`ta`) is named in the product copy but has no bundle, so it is refused on write; (d) Traditional Chinese ships as `zh-TW` only — `zh-HK`/`zh-MO` resolve to it rather than having bundles of their own. | 🚧 |
| **F-1.27** | Onboarding & default setup | First run: pick currency and language, the primary account is created automatically (**no starting balance requested**), and a useful set of default categories is seeded. | 🚧 |
| **F-1.28** | Post-download user follow-up | Users who download the product review are identified and sent the **feedback form link** and the **meeting link**. Follow-up is tracked — who was contacted, when, and whether they responded — and a user is never sent a duplicate follow-up for the same download. Blocked on [OQ-5 / OQ-6](#open-questions). | 📋 |

---

## Phase 2 — Enhanced features

Deeper assistance, monetization, and recovery from mistakes, on top of a complete MVP.

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-2.1** | Free & Premium plans | Subscription tiers for ZenZ itself: Free gives core financial management with defined usage limits; Premium gives higher or unlimited usage, advanced features, and enhanced intelligent capabilities. *(App billing — distinct from the user's own subscription tracking in F-1.7.)* | 📋 |
| **F-2.2** | Advanced financial assistant | Follow-up questions, period-over-period comparison, trend exploration, and forecasts, building on F-1.16. | 📋 |
| **F-2.3** | Activity history & undo | Review important changes to financial records, identify edited or deleted records, and undo certain changes — valuable in a ledger where mistakes move the monthly position. | 💡 |

---

## Phase 3 — Goals, debt & shared financial management

Longer-horizon personal finance, plus the extension into shared spaces. Sharing
deliberately breaks the MVP's "one user owns every row" rule; it is specified in
[domain-documentation.md Part 5](domain/domain-documentation.md#part-5--sharing--multi-user-phase-3).

### Goals & debt

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-3.1** | Savings goals | A goal has a name, target amount, optional target date, and contributions made toward it; the user sees how close they are and gets reminders. See [domain-documentation.md §1.9](domain/domain-documentation.md#19-savingsgoal--goalcontribution) and [OQ-7](#open-questions). | 🚧 |
| **F-3.2** | Debt & loan management | Track borrowed and lent money: loan amount, interest, installment amount, payment schedule, payments already made, remaining amount, and payoff progress. See [domain-documentation.md Part 2](domain/domain-documentation.md#part-2--debts--commitments-phase-3). | 📋 |

### Shared financial management

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-3.3** | Family / team spaces | A shared space where members record their own transactions, view shared information and combined summaries, and see who contributed or spent. | 📋 |
| **F-3.4** | Member permissions | Roles within a space — **Owner**, **Admin**, **Member**, **Viewer** — determining what each person can manage or view. | 📋 |
| **F-3.5** | Shared budgets | A budget defined on the space and evaluated across all relevant members' spending. | 📋 |
| **F-3.6** | Individual & family reports | Switch reports between a single member's activity and the combined family/group view. | 📋 |
| **F-3.7** | Shared savings goals | Multiple members contribute toward one goal, seeing total contributions and progress toward the target. | 📋 |
| **F-3.8** | Group expense sharing | Temporary groups for shared expenses (trips, events). Each member records expenses; the system calculates who paid, who owes, and who should receive, and produces a simplified repayment plan. | 📋 |

---

## Future considerations

Not committed. Each needs a business decision before it enters a phase.

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-F.1** | Per-account transactions & transfers | The remaining slice of the multi-account model: transactions recorded against a specific account, per-account and all-account views, and transfers between the user's own accounts that count as neither income nor expense. **Account CRUD itself (create, rename, list, soft-delete) shipped early, under F-1.1, on 2026-08-18** — what's left assumes a client can choose which account a transaction lands in, which the ledger-write path still doesn't allow. | 💡 |
| **F-F.2** | Multiple currency support | More than one currency per user, with conversion — for international users, accounts in different currencies, and Phase 3 groups whose members differ. | 💡 |
| **F-F.3** | Detailed receipt splitting | Identify individual line items on a scanned receipt and let the user categorise them separately (Groceries / Household / Personal) instead of one lump total. | 💡 |
| **F-F.4** | Automatic bank transaction sync | Synchronise transactions directly from supported banks instead of importing files (F-1.22). | 💡 |
| **F-F.5** | Quick add & home-screen access | Add a transaction without opening the app — widgets, shortcuts, fast entry. | 💡 |
| **F-F.6** | Debt payoff strategies | Recommend an ordering across multiple debts — smallest first, highest-interest first, or a comparison. | 💡 |

---

## Open questions

Business decisions required before the affected feature can be finished. Sourced
from BRD §8; the **Working assumption** column is what the code does *today* in
the absence of an answer.

| Ref | Feature | Question | Working assumption | Decision from |
|---|---|---|---|---|
| **OQ-1** | F-1.17 | With the position defined as income − expenses, "balance", "savings", and "position" are the same number. Is *savings* meant to be something distinct — money deliberately set aside? | Dashboard reports income, expenses, and position only. No separate savings figure. | Product |
| **OQ-2** | F-1.2 | Does the month boundary follow the user's local timezone, and what happens to a transaction dated in a month the user is not currently viewing? | **Yes** — month windows are computed in `User.timezone` (default `UTC`). A transaction always belongs to the month its `txnDate` falls in, whichever month is on screen. | Product / Engineering |
| **OQ-3** | F-1.10 | Budgets may carry unused amounts forward while the monthly position may not. Confirm the difference is intended. | Treated as intended — a budget is a plan, the position is a fact. | Product |
| **OQ-4** | F-1.16 | Confirm the intended definition of "saved" in *"how much did I save this month?"*, given OQ-1. | Answered as the monthly position. | Product |
| **OQ-5** | F-1.28 | Is post-download follow-up an in-product capability or an internal operational process? | Unresolved — **F-1.28 is not started** pending this. | Product |
| **OQ-6** | F-1.28 | What channel and timing — email, in-app, both; immediate or delayed? | Unresolved. | Product / Marketing |
| **OQ-7** | F-3.1 | Confirm savings goals belong in Phase 3 rather than later. | Placed in Phase 3. Note the **backend is already built** (moved down from the MVP), so this is a sequencing question only. | Product |

---

## ID mapping (2026-08-08)

The BRD renumbered the catalogue. Older commits, branches, and code comments use
the left column.

| Old ID | Old name | New ID | Note |
|---|---|---|---|
| F-1.1 | Accounts | **F-1.1** | Reshaped: multi-account → exactly one, auto-created |
| F-1.2 | Account balance tracking | **F-1.2** | Replaced: derived running balance → monthly position |
| F-1.2b | Balance reconciliation | — | 🚫 out of scope |
| F-1.3 | Transfers between accounts | **F-F.1** | Deferred to future |
| F-1.4 | Expense transactions | **F-1.3** | |
| F-1.5 | Income transactions | **F-1.4** | |
| F-1.5b | Duplicate transaction | — | Not in the BRD; dropped |
| F-1.6 | Categories & sub-categories | **F-1.5** | |
| F-1.6b | Payees | **F-1.6** | |
| F-1.7 | Recurring transactions | **F-1.7** | Now also covers subscriptions |
| F-1.8 | Transaction editing & history | **F-1.8** | |
| F-1.9 | Attach receipts / images | — | 🚫 out of scope |
| F-1.9a | Chat-based entry (NLP) | **F-1.11** | |
| F-1.9b | Auto-category detection | **F-1.14** | |
| F-1.9c | Bill / receipt scanning | **F-1.13** | |
| F-1.10 | AI-powered insights | **F-1.15** | |
| F-1.10b | Financial assistant | **F-1.16** | |
| F-1.11 | Budgets | **F-1.10** | |
| F-1.12 | Savings goals | **F-3.1** | Moved MVP → Phase 3 |
| F-1.13 | Financial summary | **F-1.17** | Net worth dropped with stored balances |
| F-1.14 | Spending analysis | **F-1.18** | |
| F-1.15 | Basic reports | **F-1.19** | |
| F-1.16 | Debt / loan management | **F-3.2** | Moved MVP → Phase 3 |
| F-1.17 | Subscription tracking | **F-1.7** | Merged into recurring |
| F-1.18 | App lock | **F-1.23** | |
| F-1.18a | Data encryption | **F-1.24** | Renamed *Data protection* |
| F-1.18b | Login history | — | Not in the BRD; dropped |
| F-1.18c | Session management | — | 🚫 out of scope |
| F-1.19 | Search & filter | **F-1.9** | |
| F-1.20 | Notifications & reminders | **F-1.20** | unchanged |
| F-1.21 | Data export | **F-1.21** | unchanged |
| F-1.22 | Data import | **F-1.22** | unchanged |
| F-1.23 | Single active currency | **F-1.25** | |
| F-1.24 | Multi-language support | **F-1.26** | |
| F-1.25 | Onboarding & defaults | **F-1.27** | |
| — | *(new)* | **F-1.28** | Post-download user follow-up |
| F-2.1 | Voice-based expense entry | **F-1.12** | Promoted Phase 2 → MVP |
| F-2.2 | Free & Premium plans | **F-2.1** | |
| F-2.3 | Advanced financial assistant | **F-2.2** | |
| F-2.4 | Category merge | — | Not in the BRD; dropped |
| F-2.5 | Audit history & undo | **F-2.3** | |
| F-3.1 | Family / team spaces | **F-3.3** | |
| F-3.1a | Member permissions | **F-3.4** | |
| F-3.1b | Shared budgets | **F-3.5** | |
| F-3.1c | Individual vs family reports | **F-3.6** | |
| F-3.2 | Shared savings goals | **F-3.7** | |
| F-3.3 | Splitwise-style expense sharing | **F-3.8** | |
| F-P.7 | Cross-currency handling & FX | **F-F.2** | |
| F-P.9 | Split a scanned receipt | **F-F.3** | |
| F-P.10 | Bank / open-banking sync | **F-F.4** | |
| F-P.11 | Widgets & quick-add | **F-F.5** | |
| F-P.12 | Debt payoff strategies | **F-F.6** | |

---

## Change log

| Date | Change |
|---|---|
| 2026-07-20 | Initial version: savings goals in MVP; single-active-currency model; NLP/OCR/AI/voice catalogued. |
| 2026-07-20 | Major expansion: accounts/transfers/income detailed; transaction edit/duplicate/search/attachments; auto-category detection & financial assistant; dashboard split into summary/analysis/reports with net worth; debt/loan management and subscription tracking added to MVP; security features added to MVP; notifications, export/import, onboarding promoted to committed MVP. Phase 3 sharing detailed. |
| 2026-07-26 | Added **Payees** as a first-class entity; transaction `payee` became a `payeeId` FK. Detailed **chat-based entry**: self-hosted Qwen2.5 via Ollama, two-step draft→confirm gate, clarification-on-uncertainty, backend field resolution, conversation logging. Added balance reconciliation via adjustment transactions. |
| 2026-08-08 | **Realigned to BRD v1.0.** Every ID renumbered (see the [mapping](#id-mapping-2026-08-08)). Accounts collapse to **exactly one per user**, auto-created, unnamed, untyped. Stored/derived balances replaced by the **monthly position** (income − expenses, per calendar month, nothing carried forward). **Transfers**, **balance reconciliation**, **receipt attachments**, and **session management** moved out of scope; **multiple accounts** and **multi-currency** to Future. **Savings goals** and **debt** moved MVP → Phase 3; **voice entry** promoted Phase 2 → MVP; **subscriptions** merged into recurring (F-1.7). Login history, category merge, and duplicate-transaction dropped. Added **F-1.28** post-download follow-up and the **open questions** table. |
| 2026-08-18 | **Reversed the one-account rule.** F-1.1's account-CRUD slice of F-F.1 (create, rename, list-active, soft-delete) pulled forward into Phase 1 — a user may now hold multiple named accounts, each with a lifecycle `status`. `Budget` (F-1.10) now links to a specific account instead of storing its own currency; `BudgetPeriod` dropped `WEEKLY` (`MONTHLY`/`YEARLY` only) and period windows are now calendar-aligned and computed in the owner's timezone rather than anchored to an arbitrary `startDate`. The remaining slice of F-F.1 — per-transaction account selection and transfers — is still not built; the ledger-write path still resolves one implicit account. |
