# Roadmap — ZenZ Money Manager

The product delivery sequence across phases. This is the single place that
orders *what ships when*; the [Features List](features-list.md) catalogues every
feature with an ID, and the domain docs specify *how* each is modeled.

| Doc | Role |
|---|---|
| [features-list.md](features-list.md) | Product feature catalogue, grouped by phase, with IDs and status. |
| [domain/domain-documentation.md](domain/domain-documentation.md) | The single consolidated domain model — core ledger, debts/loans & subscriptions, ingestion & AI, security, and multi-user sharing, organized in Parts 0–6. |

---

## Phase 1 — MVP: complete single-user finance tracker

A single user can fully manage their money in one active currency and one
language: track every account and its balance, record income/expense/transfers,
plan with budgets, save toward goals, pay down debt, track subscriptions, enter
transactions manually or via chat/receipt scan, get AI insight, and keep it all
behind a secure app lock.

**Ships:** F-1.1 – F-1.25 (see [features-list.md](features-list.md)).

- **Accounts & balances** — cash/bank/savings/credit accounts, derived balances,
  transfers.
- **Transactions** — first-class income & expense, edit/delete/duplicate, notes,
  receipt attachments, search & filter, recurring.
- **Planning, saving & debt** — budgets (rollover), savings goals, debt/loan
  management (EMI schedules, payoff plan), subscription tracking.
- **Fast entry & AI** — chat/NLP entry, auto-category detection, OCR receipts,
  AI insights, financial-assistant queries.
- **Dashboard & reports** — financial summary (incl. net worth), spending
  analysis (trends, top categories, MoM), basic reports.
- **Notifications** — budget alerts, bill/recurring/goal/subscription reminders.
- **Data ownership** — export (CSV/Excel/PDF), bank-CSV import.
- **Security** — app lock (PIN/biometric), encryption, login history, sessions.
- **Platform** — single active currency, multi-language, onboarding & seed data.

**Schema:** `V3__finance_schema.sql` (ledger + goals + loans + subscriptions),
`V4__ingestion_ai.sql` (chat, attachments, insights), plus `login_event` /
`user_session` and `app_user` security columns.

**Deferred from the MVP:** true multi-currency / FX, voice entry, monetization,
any sharing.

---

## Phase 2 — Convenience, deeper AI & monetization

**Ships:** F-2.1 – F-2.5.

- **Voice entry** (F-2.1) reuses the MVP NLP pipeline behind speech-to-text.
- **Free & Premium plans** (F-2.2) introduce tiered limits — app billing,
  distinct from the user's own subscription tracking (F-1.17).
- **Advanced financial assistant** (F-2.3) — contextual/follow-up queries and
  forecasts.
- **Category merge** (F-2.4) and **audit/undo** (F-2.5).
- **Currency switch policy** and cross-currency groundwork
  ([domain-documentation.md §0.3](domain/domain-documentation.md#03-one-active-currency-per-user)).

---

## Phase 3 — Multi-user & sharing

**Ships:** F-3.1 – F-3.3. Fully specified in
[domain-documentation.md Part 5](domain/domain-documentation.md#part-5--sharing--multi-user-phase-3).

- **Family / Team spaces** (F-3.1) with member permissions (F-3.1a), shared
  budgets (F-3.1b), and individual-vs-family reports (F-3.1c).
- **Shared savings goals** (F-3.2).
- **Splitwise-style expense sharing** (F-3.3).

This phase breaks the MVP's "one `user_id` owns every row" rule, introducing the
`space_id` ownership boundary and membership-based access — deliberately additive;
the personal ledger tables are unchanged.

**Schema:** `V5__family_spaces.sql`, `V6__shared_groups.sql`,
`V7__goal_sharing.sql`.

---

## Cross-cutting / not-yet-scheduled

Tracked as proposals in [features-list.md](features-list.md#proposed-additions-not-yet-committed):
cross-currency FX (F-P.7), receipt line-item splitting (F-P.9), bank/open-banking
sync (F-P.10), widgets & quick-add (F-P.11), and debt payoff strategies (F-P.12).

---

## Pending implementation docs

Referenced by the domain docs; authored as implementation begins:

- `domain/schema.md` — concrete SQL schema / Flyway migrations.
- `api/api-design.md` — DTOs and endpoints.
- `architecture/migration-plan.md` — migrating off the legacy habit-tracker
  domain onto the finance domain.
