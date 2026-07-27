# Features List — ZenZ Money Manager

This document is the product-level feature catalogue for ZenZ Money Manager. It
groups every planned capability by **implementation phase** and gives each a
short description. It is the companion to the domain documentation and the
[roadmap](roadmap.md).

**Domain doc:** the full domain model lives in a single consolidated document,
[domain-documentation.md](domain/domain-documentation.md).

> **How to read this doc.** Each feature has a stable ID (e.g. `F-1.3`) referenced
> from the domain docs, tickets, and test plans. IDs group by phase (`F-1.x` =
> MVP, `F-2.x` = Phase 2, `F-3.x` = Phase 3). Status is tracked per feature so
> this stays a living document.

**Legend:** ✅ Done · 🚧 In progress · 📋 Planned · 💡 Proposed (not yet committed)

---

## Phase 1 — MVP

The goal of the MVP is a **complete personal finance tracker for a single user**:
know exactly where money lives and moves, organize it, stay on budget, save and
pay down debt, enter transactions the fast way (chat, receipt scan), and get
insight — all in the user's language and chosen currency, behind a secure lock.

### 1. Accounts & balances

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-1.1** | Accounts | Create and manage the places money lives: cash wallet, bank accounts, savings accounts, credit cards. Each account has a type, currency, and a running balance. | 📋 |
| **F-1.2** | Account balance tracking | Each account's current balance is derived from the ledger (opening balance ± transactions), so totals are always accurate. See [domain-documentation.md §1.10](domain/domain-documentation.md#110-balance-derivation-invariant). | 📋 |
| **F-1.2b** | Balance reconciliation | Reconcile an account's app balance against its real-world balance. When they differ (e.g. missed or forgotten transactions), the user enters the actual balance and the app records a **balance adjustment** — an ordinary income/expense transaction for the difference, to a reserved *Adjustment* category. The balance stays **derived from the ledger** (never edited directly, [§1.10](domain/domain-documentation.md#110-balance-derivation-invariant)), so totals and reports stay consistent and the correction is fully auditable. No new entity or transaction type — an adjustment is just a `Transaction`. | 📋 |
| **F-1.3** | Transfers between accounts | Move money between the user's own accounts (e.g. *transfer $500 from Bank A to Cash Wallet*) without it counting as income or expense. | 📋 |

### 2. Transactions (income & expense)

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-1.4** | Expense transactions | Record expenses against an account with amount, category, date, payee (a [Payee](domain/domain-documentation.md#15b-payee) entity, F-1.6b), and note. | 📋 |
| **F-1.5** | Income transactions (first-class) | Record income with dedicated income categories (Salary, Business, Freelance, Investments, Gifts). Income is a first-class transaction type, not an afterthought. | 📋 |
| **F-1.5b** | Duplicate transaction | One-tap duplicate of an existing transaction for fast repeated entry. | 📋 |
| **F-1.6** | Categories & sub-categories | Create, update, and manage income/expense categories with one level of sub-categories, icons, and colors. New users start with a seeded default set (see [domain-documentation.md §1.5](domain/domain-documentation.md#15-category)). | 📋 |
| **F-1.6b** | Payees (merchants/payers) | Payees are a first-class, user-owned entity (not free text): auto-created on first use, deduped by normalized name, with autocomplete and "total spent at X" reporting. Used by manual entry, chat (F-1.9a), and OCR (F-1.9c). See [domain-documentation.md §1.5b](domain/domain-documentation.md#15b-payee). | 📋 |
| **F-1.7** | Recurring transactions (income & expense) | Templates that auto-generate transactions on a cadence — e.g. *salary $3000 on the 25th monthly*, or monthly rent. Works for both income and expense. | 📋 |
| **F-1.8** | Transaction editing & history | Edit, delete, and annotate transactions (notes/comments); full history per account/category. Balances re-derive on every edit or delete. | 📋 |
| **F-1.9** | Attach receipts / images | Attach one or more images/receipts to any transaction, manually or from a scan. See [domain-documentation.md §3.5](domain/domain-documentation.md#35-attachment-receipts--ocr). | 📋 |
| **F-1.19** | Search & filter transactions | Find transactions by free text and filter by date range, category, account, amount, and payee ([entity](domain/domain-documentation.md#15b-payee), by `payeeId`) / tag. | 📋 |

### 3. Planning, saving & debt

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-1.11** | Budgets | Spending caps per category (or overall) for a weekly / monthly / yearly period, with optional rollover of unused amounts. | 📋 |
| **F-1.12** | Savings goals | Create and track goals (target amount, optional deadline) backed by a real account, funded by contributions over time. Personal, single-owner in the MVP. See [domain-documentation.md §1.9](domain/domain-documentation.md#19-savingsgoal--goalcontribution). | 📋 |
| **F-1.16** | Debt / loan management | Track loans borrowed or lent: principal, interest, an EMI (installment) schedule, recorded repayments, and a derived payoff plan. See [domain-documentation.md §2.1–2.4](domain/domain-documentation.md#part-2--debts--commitments-mvp). | 📋 |
| **F-1.17** | Subscription tracking | Track the user's own recurring subscriptions (Netflix, Spotify, gym): cost, billing cycle, renewal date, free-trial end, and renewal reminders. A specialized recurring expense. See [domain-documentation.md §2.5](domain/domain-documentation.md#25-subscription-tracking). | 📋 |

### 4. Fast entry & intelligence (AI)

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-1.9a** | Chat-based transaction entry (NLP) | Add income and expense transactions by typing plain language — *"I spent $15 at Keells for tea things"* → a $15 Groceries expense (payee **Keells**, note *"tea things"*); *"got salary 3000"* → a Salary income. A **self-hosted model (Qwen2.5 via Ollama)** extracts intent / type / amount / category-guess / date-phrase / payee / note; the **backend** does all the resolution — amount→minor units in the user's active currency, guess→a real [Category](domain/domain-documentation.md#15-category), date-phrase→an exact timestamp in the user's timezone, and payee-name→a [Payee](domain/domain-documentation.md#15b-payee) row. The flow is **two-step**: the message returns a **draft** (no ledger write); the user **confirms** before it is saved (hard write gate, [§3.7](domain/domain-documentation.md#37-privacy-safety--language)). If the model is unsure or a field is missing, the assistant asks a **clarifying question** instead of guessing. Conversations are logged (`ChatMessage`, [§3.4](domain/domain-documentation.md#34-chatmessage)) so a saved transaction traces back to what was typed. See [domain-documentation.md Part 3](domain/domain-documentation.md#part-3--ingestion--ai-mvp--p2) and the [implementation plan](features/chat-transaction-entry-plan.md). | 📋 |
| **F-1.9b** | Auto-category detection | AI infers the category (and sub-category) from merchant/keywords — *"Spent $15 at Starbucks"* → Food & Drinks → Coffee — and the user can correct it; corrections improve future matching. | 📋 |
| **F-1.9c** | Bill / receipt scanning (OCR) | Scan a receipt/bill; the system extracts merchant, date, and total and pre-fills an expense for confirmation. | 📋 |
| **F-1.10** | AI-powered insights & reports | Natural-language insights from the user's data — spending trends, anomalies, budget risk, saving suggestions — in reports and the dashboard. | 📋 |
| **F-1.10b** | Financial assistant (chat queries) | Ask questions about your finances — *"How much did I spend on food last month?"* → *"$320, 15% more than June."* Answers from real ledger aggregates. | 📋 |

### 5. Dashboard & reports

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-1.13** | Financial summary | At-a-glance current balance, total income, total expenses, savings amount, and **net worth** (assets − debts; see [domain-documentation.md §1.11](domain/domain-documentation.md#111-net-worth-derived)). | 📋 |
| **F-1.14** | Spending analysis | Monthly spending trend, top spending categories, and this-month-vs-last-month comparison. | 📋 |
| **F-1.15** | Basic reports | Income vs. expense over time, spending by category, budget progress, and goal progress, exportable (F-1.21). | 📋 |

### 6. Notifications & reminders

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-1.20** | Notifications & reminders | Budget-limit alerts (*"You've used 90% of your Food budget"*), bill-payment and recurring-expense reminders, savings-goal reminders, and subscription renewal/trial-ending reminders. | 📋 |

### 7. Data ownership

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-1.21** | Data export | Export transactions and reports to CSV, Excel, and PDF. | 📋 |
| **F-1.22** | Data import | Import transactions from bank CSV files (with column mapping). | 📋 |

### 8. Security

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-1.18** | App lock (PIN / biometric) | Lock the app behind a PIN or biometric (fingerprint/face) after login, with a configurable auto-lock timeout. See [domain-documentation.md Part 4](domain/domain-documentation.md#part-4--security-mvp). | 📋 |
| **F-1.18a** | Data encryption | Data encrypted in transit (TLS) and at rest. | 📋 |
| **F-1.18b** | Login history | A record of account logins (time, device, location) so users can spot unauthorized access. | 📋 |
| **F-1.18c** | Session management | See active sessions/devices and remotely sign out any of them ("sign out everywhere"). | 📋 |

### 9. Platform

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-1.23** | Single active currency per user | Each user operates in **one active currency at a time**; all accounts, transactions, budgets, goals, and loans use it. Switching is supported; mixing is not (MVP). See [domain-documentation.md §0.3](domain/domain-documentation.md#03-one-active-currency-per-user). | 📋 |
| **F-1.24** | Multi-language support | UI and AI/NLP features work in multiple languages; the user picks a preferred language. | 📋 |
| **F-1.25** | Onboarding & defaults | First-run setup: pick currency + language, create the first account, and seed default categories so the app isn't empty. | 📋 |

---

## Phase 2

Phase 2 layers **convenience, deeper AI, and monetization** on the complete MVP.

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-2.1** | Voice-based expense entry | Add transactions by voice; speech is transcribed and passed through the same NLP pipeline as chat entry (F-1.9a). See [domain-documentation.md §3.1](domain/domain-documentation.md#31-the-capture-pipeline). | 📋 |
| **F-2.2** | Free & Premium plans | Subscription tiers for ZenZ itself. Free users get core functionality with defined limits (accounts, budgets, goals, AI/OCR usage); Premium unlocks higher/unlimited limits and advanced features. *(App billing — distinct from user subscription tracking F-1.17.)* | 📋 |
| **F-2.3** | Advanced financial assistant | Deeper conversational analysis — follow-up/contextual questions, comparisons, and forecasts building on F-1.10b. | 📋 |
| **F-2.4** | Category merge | Merge one category into another, reassigning its transactions. | 📋 |
| **F-2.5** | Audit history & undo | Activity log of edits/deletes with undo, valuable for a ledger where mistakes affect balances. | 💡 |

---

## Phase 3 — Multi-user

Phase 3 introduces **sharing**, which deliberately breaks the MVP's "one user owns
every row" rule. Fully specified in
[domain-documentation.md Part 5](domain/domain-documentation.md#part-5--sharing--multi-user-phase-3).

| ID | Feature | Description | Status |
|---|---|---|---|
| **F-3.1** | Family / Team spaces | A shared household/team workspace: multiple members in one space, each recording their own transactions, with a shared dashboard and reports and per-member attribution ("who spent what"). Money is genuinely pooled. | 📋 |
| **F-3.1a** | Member permissions | Roles within a space — **Owner**, **Admin**, **Member** (and Viewer) — controlling who can manage members, accounts, and budgets vs. who can only record/view. | 📋 |
| **F-3.1b** | Shared budgets | Budgets defined on the family space, evaluated across all members' transactions. | 📋 |
| **F-3.1c** | Individual vs. family reports | Toggle reports between a single member's activity and the whole family's combined view. | 📋 |
| **F-3.2** | Shared savings goals | Turn a personal goal into a shared one — invite members to contribute toward a common target and see aggregate progress. | 📋 |
| **F-3.3** | Splitwise-style expense sharing | A temporary group (e.g. friends on a trip) where everyone logs expenses; on close, the system settles **who owes whom** and suggests the minimal set of repayments. | 📋 |

---

## Proposed additions (not yet committed)

Suggestions worth considering; confirm which you want and I'll fold them into a
phase and the domain docs.

| ID | Suggestion | Why it matters | Suggested phase |
|---|---|---|---|
| **F-P.7** | Cross-currency handling & FX | The MVP fixes one currency per user. Define the currency-switch policy and (later) true multi-currency / FX — especially for Phase 3 shared groups where members differ. | Phase 2/3 |
| **F-P.9** | Split a scanned receipt into multiple categories | OCR line items could create several categorized expenses instead of one lump total. | Phase 2 |
| **F-P.10** | Bank / open-banking sync | Auto-import transactions via a bank aggregation API instead of manual CSV import. Large scope, high value. | Phase 2/3 |
| **F-P.11** | Widgets & quick-add | Home-screen widgets and a quick-add shortcut for the fastest possible entry. | Phase 2 |
| **F-P.12** | Debt payoff strategies (snowball / avalanche) | Beyond a single payoff plan (F-1.16), recommend an ordering across multiple debts. | Phase 2 |

---

## Change log

| Date | Change |
|---|---|
| 2026-07-20 | Initial version: savings goals in MVP; single-active-currency model; NLP/OCR/AI/voice catalogued. |
| 2026-07-20 | Major expansion: accounts/transfers/income detailed; transaction edit/duplicate/search/attachments; auto-category detection (F-1.9b) & financial assistant (F-1.10b); dashboard split into summary/analysis/reports with net worth; **debt/loan management (F-1.16)** and **subscription tracking (F-1.17)** added to MVP; **security** features (app lock, encryption, login history, sessions) added to MVP; notifications, export/import, onboarding promoted to committed MVP. Phase 3 sharing detailed (permissions, shared budgets, individual-vs-family reports). New proposals added. |
| 2026-07-26 | Added **Payees** as a first-class entity (**F-1.6b**); transaction `payee` becomes a `payeeId` FK, not free text (domain §1.5b; F-1.4 / F-1.19 updated). Detailed **chat-based entry (F-1.9a)**: self-hosted Qwen2.5 via Ollama, two-step draft→confirm gate, clarification-on-uncertainty, backend field resolution, conversation logging — see the [chat entry plan](features/chat-transaction-entry-plan.md). Added **balance reconciliation (F-1.2b)** via adjustment transactions (no new entity/type). |
