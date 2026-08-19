# Roadmap — ZenZ Money Manager

The product delivery sequence across phases. This is the single place that
orders *what ships when*; the [Features List](features-list.md) catalogues every
feature with an ID, and the domain docs specify *how* each is modeled.

| Doc | Role |
|---|---|
| [features-list.md](features-list.md) | Product feature catalogue, grouped by phase, with IDs and status. Tracks BRD v1.0. |
| [domain/domain-documentation.md](domain/domain-documentation.md) | The single consolidated domain model — core ledger, ingestion & AI, security, debts, and multi-user sharing, organized in Parts 0–6. |

> **Renumbered 2026-08-08.** Feature IDs follow BRD v1.0; older commits use the
> previous scheme — see the [mapping](features-list.md#id-mapping-2026-08-08).

---

## Phase 1 — MVP: complete single-user finance tracker

A single user manages their money across **one or more accounts**, in **one
currency**, and one language, on a **monthly cycle**: see this month's position (income − expenses),
record income and expenses, budget, handle everything recurring including
subscriptions, capture transactions by typing / speaking / scanning, get insight,
and keep it behind an app lock.

**Ships:** F-1.1 – F-1.28 (see [features-list.md](features-list.md)).

- **Account & position** — one auto-created account with no balance, and (since
  2026-08-18) the ability to add, rename, list, and soft-delete more; the monthly
  position is derived per calendar month across all of them.
- **Transactions** — first-class income & expense, edit/delete, notes, categories,
  payees, search & filter. Still land in one implicit account server-side —
  choosing an account per transaction isn't built yet.
- **Planning** — budgets, linked to a specific account (monthly/yearly, optional
  rollover); recurring income, expenses **and subscriptions** with renewal and
  trial-end dates.
- **Fast entry & AI** — chat/NLP entry, **voice entry**, OCR receipt scanning,
  auto-category suggestions, insights, financial-assistant queries.
- **Dashboard & reports** — monthly summary, spending analysis (trends, top
  categories, MoM), basic reports.
- **Notifications** — budget alerts, bill/recurring/renewal/trial reminders,
  pushed via **FCM** over the existing REST API
  ([plan](features/push-notifications-fcm-plan.md)); no WebSocket transport.
- **Data ownership** — export (CSV/Excel/PDF), bank-CSV/Excel import.
- **Security** — app lock (PIN/biometric), data protection in transit and at rest.
- **Platform** — active currency, multi-language, onboarding & seed data,
  post-download follow-up.

**Schema:** `V2__finance_schema.sql` (ledger, budgets, recurring, goals),
`V3__chat_ingestion.sql` (chat). Still to write: `app_user` security columns,
`ai_insight`, and `user_device` (FCM tokens for F-1.20).

**Deferred from the MVP:** per-transaction account selection & transfers (account
CRUD itself shipped 2026-08-18, ahead of schedule), multi-currency / FX, savings
goals, debt, monetization, any sharing.

**Ordering note.** Everything in the "fast entry & AI" group depends on the ledger
and on categories/payees being right first — F-1.1 → F-1.9 before F-1.11 → F-1.16.
F-1.28 is blocked on a product decision (OQ-5/OQ-6), not on code.

---

## Phase 2 — Deeper AI & monetization

**Ships:** F-2.1 – F-2.3.

- **Free & Premium plans** (F-2.1) introduce tiered limits — app billing,
  distinct from the user's own subscription tracking (F-1.7).
- **Advanced financial assistant** (F-2.2) — follow-up questions, period
  comparison, trends, forecasts.
- **Activity history & undo** (F-2.3, proposed).
- **Currency switch policy** and cross-currency groundwork
  ([domain-documentation.md §0.3](domain/domain-documentation.md#03-one-active-currency-per-user)).

---

## Phase 3 — Goals, debt & sharing

**Ships:** F-3.1 – F-3.8. Sharing is fully specified in
[domain-documentation.md Part 5](domain/domain-documentation.md#part-5--sharing--multi-user-phase-3).

- **Savings goals** (F-3.1) — *backend already built*, moved down from the MVP.
- **Debt & loan management** (F-3.2) — principal, interest, EMI schedule,
  payments, payoff progress ([Part 2](domain/domain-documentation.md#part-2--debts--commitments-phase-3)).
- **Family / team spaces** (F-3.3) with member permissions (F-3.4), shared
  budgets (F-3.5), and individual-vs-family reports (F-3.6).
- **Shared savings goals** (F-3.7).
- **Group expense sharing** (F-3.8).

This phase breaks the MVP's "one `user_id` owns every row" rule, introducing the
`space_id` ownership boundary and membership-based access — deliberately additive;
the personal ledger tables are unchanged. A shared space also needs transactions
assignable to a specific account, which is the remaining, unbuilt slice of F-F.1
(account CRUD itself is done — see below).

**Schema:** loans, `family`, `family_member`, `shared_group` and friends, plus
`space_id` columns on owned tables.

---

## Future — not scheduled

Tracked in [features-list.md](features-list.md#future-considerations): the
remaining slice of multiple accounts & transfers (F-F.1), multi-currency & FX
(F-F.2), receipt line-item splitting (F-F.3), bank sync (F-F.4), quick add &
widgets (F-F.5), and debt payoff strategies (F-F.6).

**F-F.1's account-CRUD slice shipped 2026-08-18**, ahead of schedule — create,
rename, list-active, and soft-delete now exist, and `Budget` links to a specific
account. **What's left is still the heavy part**: the ledger write path
(`Transaction`, `RecurringTransaction`) still resolves one implicit account per
user, baked in since the BRD v1.0 collapse. Letting a client choose an account
per transaction, adding transfers as a third transaction type, and per-account
figures are still a phase, not a ticket.

---

## Pending implementation docs

Referenced by the domain docs; authored as implementation begins:

- `domain/schema.md` — concrete SQL schema / Flyway migrations.
- `api/api-design.md` — DTOs and endpoints.
