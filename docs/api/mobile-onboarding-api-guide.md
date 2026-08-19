# Mobile API Guide — Onboarding & Account

Scope: the sign-up-to-first-screen flow, plus the account endpoints those screens
depend on. In call order:

1. [Register](#1-register)
2. [Verify email](#2-verify-email)
3. [Login](#3-login)
4. [Get current user](#4-get-current-user)
5. [Change password](#5-change-password)
6. [Update profile](#6-update-profile)
7. [Get available currencies](#7-get-available-currencies)
8. [Complete onboarding](#8-complete-onboarding)
9. [Get primary account](#9-get-primary-account)
10. [List active accounts](#10-list-active-accounts)
11. [Get one account](#11-get-one-account)
12. [Create account](#12-create-account)
13. [Rename account](#13-rename-account)
14. [Delete account](#14-delete-account)

Other endpoints (categories, transactions, budgets, goals, recurring, chat) exist but are out of scope for this document.

## Base URL

```
https://<host>/api/v1
```

## Response envelope

Every response — success or error — is wrapped the same way:

```json
{
  "status": "success",
  "data": { },
  "message": null,
  "errorCode": null
}
```

On failure `status` is `"error"`, `data` is `null`, and `message`/`errorCode` are populated:

```json
{
  "status": "error",
  "data": null,
  "message": "Email already in use",
  "errorCode": "E1013"
}
```

| HTTP | errorCode | Meaning |
|---|---|---|
| 400 | `E1013` | Bad request (business-rule rejection, e.g. invalid password, duplicate email) |
| 400 | `E1015` | Bean validation failure — message is `field: reason` |
| 401 | varies (e.g. `INVALID_PASSWORD`, `INVALID_TOKEN`, `NO_TOKEN`) | Not authenticated / bad credentials / bad token |
| 403 | `E1014` | Forbidden — authenticated but not allowed |
| 404 | `E1010` | Not found |
| 429 | `E1051` (OTP-related) | Rate limited — see `Retry-After` header |

## Authentication

Protected endpoints require:

```
Authorization: Bearer <accessToken>
```

- Access token: expires in **1 hour** (3,600,000 ms).
- Refresh token: expires in **30 days** (2,592,000,000 ms).
- A refresh token cannot be used as an access token and vice versa — the server checks token type.
- Endpoints marked **public** below need no token at all.

---

## 1. Register

```
POST /api/v1/register
```
**Auth:** public

Creates a user in `PENDING` status and emails a 6-digit OTP for verification. Registration does **not** log the user in — no tokens are returned here.

### Request body

| Field | Type | Required | Notes |
|---|---|---|---|
| `email` | string | yes | Lowercased/trimmed server-side. Disposable-domain addresses are rejected. |
| `password` | string | yes | 8–128 chars, must contain a letter, a digit, a special character, and no whitespace. |
| `locale` | string | no | BCP-47, e.g. `si-LK`. Seeds a provisional currency; ignored if unparseable. |
| `timezone` | string | no | IANA zone, e.g. `Asia/Colombo`. Seeds where the user's calendar months start; ignored if unparseable. |

```json
{
  "email": "jane@example.com",
  "password": "ChangeMe123!",
  "locale": "en-US",
  "timezone": "America/New_York"
}
```

> **`locale` and `timezone` are hints, not enumerated choices — there's no fixed list to validate against, unlike currency (§7).**
> - `timezone` must be a valid **IANA/Olson zone ID** (e.g. `America/New_York`, `Asia/Colombo`) — the same format both Android (`TimeZone.getDefault().getID()`) and iOS (`NSTimeZone.local.identifier`) already report natively, so just pass the OS value through.
> - `locale` is free-form BCP-47; only its country part is used, to guess a starting currency.
> - Neither is validated strictly here: an unparseable value is **silently ignored** and registration still succeeds — this endpoint is lenient because these are just hints. That's different from [Complete Onboarding](#8-complete-onboarding), where the user explicitly picks a timezone/currency and a bad value is rejected with `400`.

### Response `data`

```json
{
  "userId": "3f1e7c2a-...-uuid",
  "email": "jane@example.com",
  "message": "Verification code sent"
}
```

### Errors

- `400 E1013` — invalid email, disposable email domain, weak password, or email already in use.
- `429` — the OTP send is rate-limited per email: **3 / 10 min, 5 / hour, 10 / day** (all enforced together, fail-closed). Retry after the `Retry-After` header's value in seconds.

---

## 2. Verify Email

```
POST /api/v1/verify-email
```
**Auth:** public

Confirms the OTP from registration, activates the account (`PENDING` → `ACTIVE`), and — unlike register — **returns tokens**, so the app can go straight to a logged-in state.

### Request body

| Field | Type | Required | Notes |
|---|---|---|---|
| `email` | string | yes | Same email used at registration. |
| `code` | string | yes | The 6-digit code. Valid for 10 minutes from issuance; 5 attempts max before it's invalidated. |

```json
{
  "email": "jane@example.com",
  "code": "744555"
}
```

### Response `data`

```json
{
  "accessToken": "eyJhbGciOi...",
  "refreshToken": "eyJhbGciOi..."
}
```

### Errors

- `400 E1013` — no account for that email, or the code is wrong/expired/already used.

---

## 3. Login

```
POST /api/v1/authenticate
```
**Auth:** public

Email/password login for an already-verified account.

### Request body

| Field | Type | Required |
|---|---|---|
| `email` | string | yes |
| `password` | string | yes |

```json
{
  "email": "jane@example.com",
  "password": "ChangeMe123!"
}
```

### Response `data`

Same shape as verify-email:

```json
{
  "accessToken": "eyJhbGciOi...",
  "refreshToken": "eyJhbGciOi..."
}
```

### Errors

- `401` — wrong email/password, or account not yet verified.

> Social login (`/authenticate/google`, `/authenticate/apple`, `/authenticate/facebook`) exists but is out of scope here.

---

## 4. Get Current User

```
GET /api/v1/me
```
**Auth:** required (`Bearer <accessToken>`)

Returns the caller's identity and preferences. The client should call this right after login/verify to decide whether to route into onboarding (`onboarded: false`) or straight to the app.

### Response `data`

```json
{
  "email": "jane@example.com",
  "firstName": null,
  "lastName": null,
  "authenticated": true,
  "onboarded": false,
  "activeCurrency": "USD",
  "language": "en",
  "timezone": "America/New_York"
}
```

| Field | Notes |
|---|---|
| `onboarded` | `false` means `activeCurrency`/`language`/`timezone` are provisional guesses from signup, not confirmed choices. |
| `activeCurrency` | ISO-4217 code. |

### Errors

- `401` — missing/invalid/expired access token.

---

## 5. Change Password

```
POST /api/v1/change-password
```
**Auth:** required (`Bearer <accessToken>`)

Verifies the caller's current password before setting a new one. Only works for password-auth accounts — OAuth accounts (Google/Apple/Facebook) have nothing to change.

### Request body

| Field | Type | Required | Notes |
|---|---|---|---|
| `currentPassword` | string | yes | Must match the account's existing password. |
| `newPassword` | string | yes | Same rules as registration (8–128 chars, letter + digit + special char, no whitespace). |

```json
{
  "currentPassword": "ChangeMe123!",
  "newPassword": "NewPassw0rd!"
}
```

### Response `data`

```json
{ "message": "Password changed" }
```

### Errors

- `400 E1013` — new password fails validation, or the account isn't password-auth (e.g. `"This account uses google login and has no password to change."`).
- `401 INVALID_PASSWORD` — `currentPassword` doesn't match.

---

## 6. Update Profile

```
PUT /api/v1/me
```
**Auth:** required (`Bearer <accessToken>`)

Updates the caller's own name. Either field may be omitted/blank to leave the existing value unchanged — this is a partial update, not a full replace.

### Request body

| Field | Type | Required | Notes |
|---|---|---|---|
| `firstName` | string | no | Max 120 chars. |
| `lastName` | string | no | Max 120 chars. |

```json
{
  "firstName": "Ada",
  "lastName": "Lovelace"
}
```

### Response `data`

Returns the full updated `MeResponse` (same shape as [Get Current User](#4-get-current-user)):

```json
{
  "email": "jane@example.com",
  "firstName": "Ada",
  "lastName": "Lovelace",
  "authenticated": true,
  "onboarded": false,
  "activeCurrency": "USD",
  "language": "en",
  "timezone": "America/New_York"
}
```

### Errors

- `400 E1015` — a field exceeds 120 chars.
- `401` — missing/invalid access token.

---

## 7. Get Available Currencies

```
GET /api/v1/onboarding/currencies
```
**Auth:** required (`Bearer <accessToken>`)

Returns the full ISO-4217 currency list for a currency picker. This is the same list the server validates against, so any code shown here is a valid choice.

### Response `data`

```json
[
  { "code": "AED", "name": "United Arab Emirates Dirham", "fractionDigits": 2 },
  { "code": "BHD", "name": "Bahraini Dinar", "fractionDigits": 3 },
  { "code": "JPY", "name": "Japanese Yen", "fractionDigits": 0 },
  { "code": "USD", "name": "US Dollar", "fractionDigits": 2 }
]
```

| Field | Notes |
|---|---|
| `code` | ISO-4217 currency code — pass this back as `currency` when completing onboarding. |
| `name` | Display name (English). |
| `fractionDigits` | Decimal places for this currency. Use it to convert a display amount to/from minor units (`amount = round(displayValue * 10^fractionDigits)`), e.g. 2 for USD, 0 for JPY, 3 for BHD. |

Sorted alphabetically by `code`. Pseudo-currencies (precious metals, "no currency" codes) are excluded.

---

## 8. Complete Onboarding

```
POST /api/v1/onboarding
```
**Auth:** required (`Bearer <accessToken>`)

First-run setup: the user confirms or corrects the currency/language/timezone
registration guessed, and everything else is provisioned automatically — the
primary account (no starting balance is asked for; there is no balance
anywhere in this domain) and a default set of categories. Idempotent: calling
it again updates the preferences without creating a second account or
duplicate categories.

### Request body

| Field | Type | Required | Notes |
|---|---|---|---|
| `currency` | string | yes | ISO-4217, exactly 3 chars, e.g. `LKR`. Must be one of the codes from [Get Available Currencies](#7-get-available-currencies) — anything else is rejected. |
| `language` | string | no | BCP-47, e.g. `en`. Max 10 chars. Omit to leave the existing value unchanged. |
| `timezone` | string | no | IANA zone, e.g. `Asia/Colombo`. Max 50 chars. Decides where the user's calendar months start. Omit to leave the existing value unchanged. |

```json
{
  "currency": "USD",
  "language": "en",
  "timezone": "America/New_York"
}
```

### Response `data`

```json
{
  "accountId": "9b1e2c44-...-uuid",
  "currency": "USD",
  "language": "en",
  "timezone": "America/New_York",
  "categoryCount": 12
}
```

| Field | Notes |
|---|---|
| `accountId` | The primary account — created here if it didn't already exist (see [§9](#9-get-primary-account)). |
| `categoryCount` | Total categories the user now has: the seeded default set, or their existing ones if they'd already been seeded. |

### Errors

- `400 E1013` — currency code isn't on the supported list, timezone isn't a valid IANA zone, or the caller already completed onboarding with a **different** currency (changing a confirmed currency isn't supported yet — re-submitting the *same* currency is fine and just updates language/timezone).
- `400 E1015` — currency isn't exactly 3 characters, or language/timezone exceed their max length.
- `401` — missing/invalid access token.

---

## 9. Get Primary Account

```
GET /api/v1/account
```
**Auth:** required (`Bearer <accessToken>`)

Returns the caller's primary account — the oldest `ACTIVE` one, and the
account every transaction and recurring item resolves to today (there's no
way yet for a client to choose which account a transaction lands in).
Auto-creates it if it doesn't exist yet, provided the caller has a currency
set — in practice this means it also works right after registration, before
onboarding runs, as long as the registration `locale` hint resolved to one.

### Response `data`

```json
{
  "id": "9b1e2c44-...-uuid",
  "name": null,
  "currency": "USD",
  "status": "ACTIVE",
  "createdTime": 1755500000000
}
```

| Field | Notes |
|---|---|
| `name` | `null` for an auto-provisioned account until the user renames it. |
| `status` | `ACTIVE`, `INACTIVE`, or `DELETED`. |
| `createdTime` | Epoch milliseconds. |

### Errors

- `400 E1013` — no active currency set yet (registration's locale hint didn't resolve to one, and onboarding hasn't run — call [Complete Onboarding](#8-complete-onboarding) first).
- `401` — missing/invalid access token.

---

## 10. List Active Accounts

```
GET /api/v1/account/active
```
**Auth:** required (`Bearer <accessToken>`)

Returns every `ACTIVE` account the caller currently holds — a user may hold
more than one. Unlike [Get Primary Account](#9-get-primary-account), this
never creates anything, so it can return an empty array before any account
exists.

### Response `data`

```json
[
  { "id": "9b1e2c44-...-uuid", "name": null, "currency": "USD", "status": "ACTIVE", "createdTime": 1755500000000 },
  { "id": "a72f0e91-...-uuid", "name": "Savings", "currency": "USD", "status": "ACTIVE", "createdTime": 1755501200000 }
]
```

### Errors

- `401` — missing/invalid access token.

---

## 11. Get One Account

```
GET /api/v1/account/{id}
```
**Auth:** required (`Bearer <accessToken>`)

Fetch one specific account by id, scoped to the caller.

### Response `data`

Same shape as [Get Primary Account](#9-get-primary-account).

### Errors

- `404 E1010` — no account with that id owned by the caller.
- `401` — missing/invalid access token.

---

## 12. Create Account

```
POST /api/v1/account
```
**Auth:** required (`Bearer <accessToken>`)

Adds another account for the caller, alongside the auto-provisioned primary
one — e.g. a "Savings" account. No starting balance is requested; there is no
balance column on an account at all. The new account always inherits the
caller's active currency — there's no way to give it a different currency.

### Request body

| Field | Type | Required | Notes |
|---|---|---|---|
| `name` | string | no | Max 100 chars. Omit or leave blank to create an unnamed account. |

```json
{ "name": "Savings" }
```

### Response `data`

Same shape as [Get Primary Account](#9-get-primary-account), for the new account.

### Errors

- `400 E1013` — no active currency set yet (complete onboarding first).
- `400 E1015` — `name` exceeds 100 chars.
- `401` — missing/invalid access token.

---

## 13. Rename Account

```
PUT /api/v1/account/{id}/name
```
**Auth:** required (`Bearer <accessToken>`)

Renames one of the caller's own accounts.

### Request body

| Field | Type | Required | Notes |
|---|---|---|---|
| `name` | string | yes | Non-blank, max 100 chars. |

```json
{ "name": "Everyday Checking" }
```

### Response `data`

Same shape as [Get Primary Account](#9-get-primary-account), with the new name.

### Errors

- `400 E1013` — the account is already deleted.
- `400 E1015` — `name` is blank or exceeds 100 chars.
- `404 E1010` — no account with that id owned by the caller.
- `401` — missing/invalid access token.

---

## 14. Delete Account

```
DELETE /api/v1/account/{id}
```
**Auth:** required (`Bearer <accessToken>`)

Soft-deletes one of the caller's accounts (`status` → `DELETED`; the row and
its transactions are kept). A user must always keep at least one `ACTIVE`
account, so this is refused on the last one.

### Response `data`

```json
{ "message": "Account deleted" }
```

### Errors

- `400 E1013` — this is the caller's last `ACTIVE` account, or it's already deleted.
- `404 E1010` — no account with that id owned by the caller.
- `401` — missing/invalid access token.

---

## Suggested client flow

```
register → (email OTP) → verify-email → [store tokens]
                                            │
                                            ▼
                                        GET /me
                                            │
                             onboarded=false?  ── yes ──► GET /onboarding/currencies
                                            │                     │
                                            │                     ▼
                                            │            (currency picker; user picks one)
                                            │                     │
                                            │                     ▼
                                            │            POST /onboarding (§8) → primary account
                                            │             + default categories created
                                            ▼                     │
                                     onboarded=true ◄──────────────┘
                                            │
                                            ▼
                                   GET /account (§9) → home screen
```

`change-password` and `PUT /me` (profile update) are account-settings actions,
called any time after login — not part of the first-run sequence above.
Adding, renaming, or deleting further accounts (§§10–14) is also a
settings-time action, not part of first run.
