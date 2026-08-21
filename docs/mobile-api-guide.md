# Mobile API Guide — Onboarding, Account, Transactions, Recurring, Budgets & Categories

Scope: the sign-up-to-first-screen flow, the account endpoints those screens
depend on, the transaction ledger the app is built around, the recurring
commitments and subscriptions that post into it, the budgets planned against it,
and the categories everything is filed under. Roughly in call order:

1. [Register](#1-register)
2. [Verify email](#2-verify-email)
3. [Login](#3-login)
4. [Get current user](#4-get-current-user)
5. [Change password](#5-change-password)
6. [Update profile](#6-update-profile)
7. [Get available currencies](#7-get-available-currencies)
8. [Complete onboarding](#9-complete-onboarding)
9. [Get primary account](#10-get-primary-account)
10. [List active accounts](#11-list-active-accounts)
11. [Get one account](#12-get-one-account)
12. [Create account](#13-create-account)
13. [Rename account](#14-rename-account)
14. [Delete account](#15-delete-account)
15. [Create transaction](#16-create-transaction)
16. [List transactions](#17-list-transactions)
17. [Get one transaction](#18-get-one-transaction)
18. [Update transaction](#19-update-transaction)
19. [Delete transaction](#20-delete-transaction)
20. [Monthly summary](#21-monthly-summary)
21. [Category breakdown](#22-category-breakdown)
22. [Create budget](#23-create-budget)
23. [List budgets](#24-list-budgets)
24. [Monthly budget summary](#25-monthly-budget-summary)
25. [Get one budget](#26-get-one-budget)
26. [Update budget](#27-update-budget)
27. [Archive budget](#28-archive-budget)
28. [Delete budget](#29-delete-budget)
29. [List categories](#30-list-categories)
30. [Create category](#31-create-category)
31. [Get one category](#32-get-one-category)
32. [Update category](#33-update-category)
33. [Delete category](#34-delete-category)
34. [Seed default categories](#35-seed-default-categories)
35. [Create recurring template](#36-create-recurring-template)
36. [List recurring templates](#37-list-recurring-templates)
37. [Upcoming payments](#38-upcoming-payments)
38. [Get one recurring template](#39-get-one-recurring-template)
39. [Update recurring template](#40-update-recurring-template)
40. [Delete recurring template](#41-delete-recurring-template)

Other endpoints (goals, chat) exist but are out of scope for this document.

> **Categories are last but needed first.** Every transaction carries a `categoryId`
> ([§16](#16-create-transaction)), so a client cannot record anything without one — but
> it never has to *create* one to get started: [onboarding](#9-complete-onboarding) seeds
> a full set. §§29–34 are the category-management screen, which is settings-time work
> like accounts, not part of first run. Read [Categories — what the app must know
> first](#categories--what-the-app-must-know-first) before building against them.

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

`errorCode` is the stable half of the contract and `message` is not: branch on the code, and treat
`message` as display text that may be reworded. Every code the API can answer with is listed below
— there are no others, and one code always means one thing.

### `message` is localised; `errorCode` is not (F-1.26)

**`message` comes back in the caller's language.** Nothing else about the response changes: the
HTTP status, the `errorCode`, and every field name and enum value are identical in every language.
A client that branches on `errorCode` needs no changes at all.

The language is chosen server-side, in this order:

1. **The signed-in user's stored `language`** (set at [onboarding](#9-complete-onboarding), changed
   later via [Update Profile](#6-update-profile)). This wins — it is the language the user picked.
2. **The `Accept-Language` request header**, for anything with no signed-in user: register, login,
   verify-email, forgot-password, and any request whose token was rejected.
3. **English.**

Supported today, 12 bundles — fetch this list at runtime from
[Get Available Languages](#8-get-available-languages) rather than hard-coding it:

| Tag | Language | Tag | Language |
|---|---|---|---|
| `en` | English | `de` | German |
| `zh-CN` | Chinese (Simplified) | `it` | Italian |
| `zh-TW` | Chinese (Traditional) | `ru` | Russian |
| `fr` | French | `ja` | Japanese |
| `es` | Spanish | `ko` | Korean |
| `pt` | Portuguese | `si` | Sinhala |

**Region is ignored; script is not.** `fr-CA`, `pt-BR`, `es-MX` and `si-LK` all resolve to their
language, so there is nothing regional to send. Chinese is the exception, because the script changes
the text rather than the formatting:

| You send | You get |
|---|---|
| `zh-TW`, `zh-HK`, `zh-MO`, `zh-Hant`, `zh-Hant-TW` | **Traditional** (`zh-TW`) |
| `zh-CN`, `zh-SG`, `zh-Hans`, or a bare `zh` | **Simplified** (`zh-CN`) |

A tag outside the set falls back to English rather than being an error, and `Accept-Language` is
honoured with its q-values, so `nl;q=1.0, zh-TW;q=0.8` gets Traditional Chinese. Sending
`Accept-Language` on every request is harmless and is the right default: it is what the user sees
before they have signed in.

### What gets stored

Anywhere you *write* a language — `locale` on [Register](#1-register), `language` on
[Update Profile](#6-update-profile) and [Complete Onboarding](#9-complete-onboarding) — the server
stores **the tag of the bundle it matched, not the tag you sent**:

| You send | Stored, and returned by `GET /me` |
|---|---|
| `en-US`, `en-GB`, `en-UK`, `en_US`, `en-Latn-US` | `en` |
| `pt-BR`, `pt-PT` | `pt` |
| `fr-CA`, `fr-FR` | `fr` |
| `si-LK` | `si` |
| `zh-HK`, `zh-MO`, `zh-Hant` | `zh-TW` |
| `zh-SG`, `zh-Hans`, `zh` | `zh-CN` |

So there is exactly one stored value per bundle, and `GET /me` never surprises you with a regional
variant you have to parse. **Round-tripping is safe**: whatever `GET /me` returns can be sent
straight back. Only Chinese keeps a region, because there the region names a genuinely different
bundle.

Underscores are accepted (`en_US`) since some platforms produce them, and case is not significant.

> **Translations other than English are machine-assisted and not yet reviewed by native speakers.**
> Treat the wording as provisional; the `errorCode` is not.

Two things stay English on purpose:

- **Bean-validation field names** in an `E1015` message (`amount: must be greater than 0`). The
  field name is a contract identifier you branch on; your own form label is yours to translate.
  Only the reason after the colon is localised.
- **Server logs.** Unrelated to the response, but it is why a support conversation quoting
  `X-Correlation-Id` still finds an English line at the other end.

### Generic outcomes

| HTTP | errorCode | Meaning |
|---|---|---|
| 400 | `E1013` | Bad request — a business rule rejected the call (duplicate email, wrong category kind, last active account) |
| 400 | `E1015` | Bean validation failure — `message` is `field: reason` |
| 403 | `E1014` | Forbidden — authenticated but not allowed |
| 404 | `E1010` | Not found (or not owned by the caller) |
| 500 | `E1000` | Server defect. `message` is always generic; quote the `X-Correlation-Id` in a bug report |

> **A body the server cannot parse answers `400 E1013`.** That covers malformed JSON
> and — the case a client hits by accident — **any enum field sent with a value outside
> its set**: `kind` on a category, `type` on a transaction, `period` on a budget,
> `paymentMethod` on either a transaction or a recurring template. Deserialization
> failed, so the request never reached the validation layer and there is no `E1015`
> field message to go with it: the `message` is the generic "Bad request" and the
> offending field is not named. Send enum values exactly as this document spells them.
> Unknown paths (`404 E1010`) and unsupported methods (`405 E1013`) are reported the
> same way.
>
> *(This was `500 E1000` before 2026-08-21. If you are testing against an older build,
> treat a `500` on a write as "check the payload" first.)*

### Rate limits — always with a `Retry-After` header

| HTTP | errorCode | Fires on |
|---|---|---|
| 429 | `E1051` | Verification-code issuance (register, resend, forgot-password): 3 per 10 min, 5 per hour, 10 per day, per email |
| 429 | `E1052` | Chat messages, and the AI insight path inside chat |
| 429 | `E1053` | Failed logins for one account — **also locks the account**; the user must reset their password |

### Authentication and identity

| HTTP | errorCode | Meaning | What the client should do |
|---|---|---|---|
| 401 | `E1060` | No credential presented | Send the user to sign-in |
| 401 | `E1061` | Token malformed, wrongly signed, or missing claims | Sign out, sign in again |
| 401 | `E1062` | Access token expired | Call `/refresh-token`, retry once |
| 401 | `E1063` | Wrong token type (refresh sent as access, or access sent to `/refresh-token`) | Fix the client; do not retry |
| 401 | `E1064` | Token is valid but names no existing account | Sign out |
| 401 | `E1065` | Account not active — email not verified yet | Route to email verification |
| 401 | `E1066` | Account locked (see `E1053`) | Route to password reset |
| 401 | `E1067` | Invalid email or password | Show one message; this code deliberately cannot tell you which half was wrong |
| 401 | `E1068` | `currentPassword` doesn't match, on change-password | Re-prompt |
| 401 | `E1069` | The account was created with a social login | Show the provider's button; `message` names the provider |

### Social sign-in

| HTTP | errorCode | Meaning |
|---|---|---|
| 401 | `E1070` | The social sign-in request was missing or had an unsupported `type`/value |
| 401 | `E1071` | The provider's token failed verification (bad issuer/audience, expired, issued for another app) |
| 401 | `E1072` | The provider supplied no verified email address — offer another sign-in method |
| 502 | `E1304` / `E1305` / `E1306` | Google / Apple / Facebook could not be reached, or answered with an unusable response. Transient: retry |
| 503 | `E1005` | That sign-in method is not configured on this server. Not the user's fault; hide the button |

> **`E1005`, `E130x` and `E1062` are new in the 2026-08-20 status-code release, and the old 401 name
> codes (`NO_TOKEN`, `INVALID_TOKEN`, `VALIDATION_FAILED`, `CONFIG_MISSING`, `INVALID_USERNAME`,
> `INVALID_PASSWORD`, `USER_LOCKED`, `USER_NOT_ACTIVE`) are gone** — every 401 now carries an
> `E1nnn` code. Two responses also changed status: an unconfigured OAuth provider was a `401` and is
> now `503`, and a provider that cannot be reached was a `401` and is now `502`. A client that
> treated any `401` as "sign out" will now correctly leave the user signed in through a provider
> outage.

### Codes not yet in use

`E11xx` is reserved for per-feature domain codes (account, category, transaction, budget, recurring,
savings goal, chat). Today those rejections all answer `E1013`; as individual ones are promoted to
their own code they will be listed here and in the endpoint's own error list. A client should treat
an unknown `E1nnn` as it treats the HTTP status.

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
| `locale` | string | no | BCP-47, e.g. `si-LK`. Seeds a provisional currency **and** the preferred language; ignored if unparseable. The language it seeds is the language the verification email is written in, so send it — this is the one message that goes out before the user has seen a picker. Send the full tag for Chinese (`zh-TW` vs `zh-CN`); the region matters for nothing else. Falls back to `en` when the locale names a language the server cannot answer in. |
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
> - Neither is validated strictly here: an unparseable value is **silently ignored** and registration still succeeds — this endpoint is lenient because these are just hints. That's different from [Complete Onboarding](#9-complete-onboarding), where the user explicitly picks a timezone/currency and a bad value is rejected with `400`.

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

- `401 E1067` — wrong email or password (the two are indistinguishable on purpose).
- `401 E1065` — account not yet verified.
- `401 E1066` — account locked after too many failed attempts.
- `401 E1069` — the account was created with a social login; `message` names the provider.
- `429 E1053` — too many failed attempts; the account is now locked.

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
| `language` | Always a tag from the [supported list](#message-is-localised-errorcode-is-not-f-126), never the regional variant you sent — see [What gets stored](#what-gets-stored). |

### Errors

- `401 E1060` / `E1061` / `E1062` — no token, an invalid one, or an expired one.

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
- `401 E1068` — `currentPassword` doesn't match.

---

## 6. Update Profile

```
PUT /api/v1/me
```
**Auth:** required (`Bearer <accessToken>`)

Updates the caller's own name and preferred language. Any field may be omitted/blank to leave the existing value unchanged — this is a partial update, not a full replace.

### Request body

| Field | Type | Required | Notes |
|---|---|---|---|
| `firstName` | string | no | Max 120 chars. |
| `lastName` | string | no | Max 120 chars. |
| `language` | string | no | BCP-47 tag. Must be one the server can answer in (see the [supported list](#message-is-localised-errorcode-is-not-f-126)); anything else is rejected rather than stored. The value is stored canonically, so `pt-BR` is stored as `pt` and `zh-HK` as `zh-TW`. This is the **only** way to change the language after onboarding. |

```json
{
  "firstName": "Ada",
  "lastName": "Lovelace",
  "language": "si"
}
```

Changing `language` takes effect on the **next** response — the one confirming the change is still
rendered in the old language, because the message is resolved before the new preference is saved.

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

- `400 E1013` — `language` is not one the server supports.
- `400 E1015` — a field exceeds its maximum length.
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

## 8. Get Available Languages

```
GET /api/v1/onboarding/languages
```
**Auth:** required (`Bearer <accessToken>`)

The language picker's options — **the same allowlist** `POST /onboarding` and
[Update Profile](#6-update-profile) validate against, so a client that populates its picker from
here can never offer a language the server would then refuse. Order is the server's configured
order, English first.

### Response `data`

```json
[
  { "tag": "en",    "name": "English",               "nativeName": "English" },
  { "tag": "zh-CN", "name": "Chinese (Simplified)",  "nativeName": "中文 (简体)" },
  { "tag": "zh-TW", "name": "Chinese (Traditional)", "nativeName": "中文 (繁體)" },
  { "tag": "fr",    "name": "French",                "nativeName": "Français" }
]
```

*(First four of twelve; the full list is returned.)*

| Field | Notes |
|---|---|
| `tag` | What to send back as `language`, and what `GET /me` returns. Send it verbatim. |
| `name` | The language's name **in English** — for a fallback UI, or a picker that stays in one language. |
| `nativeName` | The name in the language itself. **Show this in the picker**: it is the one screen a user reads *before* the app speaks their language, and "Deutsch" is findable to someone who cannot read "German". |

Chinese is named by **script, not region** — "Chinese (Simplified)", not "Chinese (China)" — because
the script is what the two entries actually differ by.

### Errors

- `403 E1014` — no token at all. A request with no credential reaches this endpoint as anonymous and
  is refused by method security, so it is a 403, not a 401.
- `401 E1061` / `E1062` — a token that was presented but is invalid or expired.

---

## 9. Complete Onboarding

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
| `language` | string | no | BCP-47, e.g. `en`. Max 10 chars. Must be one the server can answer in (see the [supported list](#message-is-localised-errorcode-is-not-f-126)); anything else is rejected. Stored canonically (`pt-BR` → `pt`, `zh-HK` → `zh-TW`). Omit to leave the existing value unchanged. |
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
  "categoryCount": 16
}
```

| Field | Notes |
|---|---|
| `accountId` | The primary account — created here if it didn't already exist (see [§10](#10-get-primary-account)). |
| `categoryCount` | Total categories the user now has: the seeded default set (16 — see [§35](#35-seed-default-categories)), or their existing ones if they'd already been seeded. |

### Errors

- `400 E1013` — currency code isn't on the supported list, `language` isn't one the server supports, timezone isn't a valid IANA zone, or the caller already completed onboarding with a **different** currency (changing a confirmed currency isn't supported yet — re-submitting the *same* currency is fine and just updates language/timezone).
- `400 E1015` — currency isn't exactly 3 characters, or language/timezone exceed their max length.
- `401` — missing/invalid access token.

---

## 10. Get Primary Account

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

- `400 E1013` — no active currency set yet (registration's locale hint didn't resolve to one, and onboarding hasn't run — call [Complete Onboarding](#9-complete-onboarding) first).
- `401` — missing/invalid access token.

---

## 11. List Active Accounts

```
GET /api/v1/account/active
```
**Auth:** required (`Bearer <accessToken>`)

Returns every `ACTIVE` account the caller currently holds — a user may hold
more than one. Unlike [Get Primary Account](#10-get-primary-account), this
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

## 12. Get One Account

```
GET /api/v1/account/{id}
```
**Auth:** required (`Bearer <accessToken>`)

Fetch one specific account by id, scoped to the caller.

### Response `data`

Same shape as [Get Primary Account](#10-get-primary-account).

### Errors

- `404 E1010` — no account with that id owned by the caller.
- `401` — missing/invalid access token.

---

## 13. Create Account

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

Same shape as [Get Primary Account](#10-get-primary-account), for the new account.

### Errors

- `400 E1013` — no active currency set yet (complete onboarding first).
- `400 E1015` — `name` exceeds 100 chars.
- `401` — missing/invalid access token.

---

## 14. Rename Account

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

Same shape as [Get Primary Account](#10-get-primary-account), with the new name.

### Errors

- `400 E1013` — the account is already deleted.
- `400 E1015` — `name` is blank or exceeds 100 chars.
- `404 E1010` — no account with that id owned by the caller.
- `401` — missing/invalid access token.

---

## 15. Delete Account

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

## Transactions — what the app must know first

Three rules shape every call below. Getting one wrong produces numbers that look
plausible and are silently wrong, so they are worth reading once.

**1. Money is minor units, always.** `amount` is a whole number of the currency's
smallest unit — `1050` with `USD` is $10.50. Never send a decimal. Convert with the
`fractionDigits` from [Get Available Currencies](#7-get-available-currencies):

```
amount        = round(displayValue * 10^fractionDigits)     // 10.50 USD → 1050
displayValue  = amount / 10^fractionDigits                  // 1050     → 10.50
```

`fractionDigits` is **not** always 2 — JPY is 0, BHD is 3. Hard-coding `* 100` breaks
those currencies.

**2. `amount` is always positive.** Direction comes from `type`, never from the sign.
An expense of $10.50 is `{"type": "EXPENSE", "amount": 1050}` — not `-1050`. A negative
or zero amount is rejected.

**3. The app does not choose the account or the currency.** Neither is accepted in a
request body. The server stamps the transaction with the caller's active currency and
resolves it to their primary account ([§10](#10-get-primary-account)) — so there is
currently **no way to post a transaction to a specific account**, even though a user
may hold several. Both come back on the response.

There is no stored balance anywhere. The figure for a month is summed from these rows
on read — see [Monthly summary](#21-monthly-summary).

### The transaction object

Every endpoint in this section returns this shape:

```json
{
  "id": "7c4a1b90-...-uuid",
  "accountId": "9b1e2c44-...-uuid",
  "type": "EXPENSE",
  "categoryId": "c31d0a77-...-uuid",
  "amount": 1050,
  "currency": "USD",
  "txnDate": 1755500000000,
  "payeeId": "p88f2e10-...-uuid",
  "note": "burger",
  "paymentMethod": "CARD",
  "tags": ["lunch"],
  "recurringId": null
}
```

| Field | Notes |
|---|---|
| `type` | `INCOME` or `EXPENSE`. |
| `amount` | Minor units, always positive. |
| `currency` | ISO-4217, stamped server-side from the user's active currency. |
| `txnDate` | Epoch milliseconds. |
| `payeeId` | `null` unless a `payeeName` was sent; the server resolves the name to a payee, creating it on first use. |
| `paymentMethod` | `CASH`, `CARD`, `BANK_TRANSFER`, `WALLET`, `OTHER`, or `null`. **A label, not an account** — `null` means the user did not say, and every row recorded before this field existed has `null`. Do not substitute a default when rendering it. |
| `recurringId` | Non-null means this row was generated automatically from a recurring template, not entered by hand — useful for badging it in the list. |

---

## 16. Create Transaction

```
POST /api/v1/transactions
```
**Auth:** required (`Bearer <accessToken>`)

### Request body

| Field | Type | Required | Notes |
|---|---|---|---|
| `type` | string | yes | `INCOME` or `EXPENSE`. |
| `categoryId` | string | yes | Must be one of the caller's own categories, and its kind must match `type` — an `INCOME` transaction needs an income category. |
| `amount` | number | yes | Minor units, **positive**. |
| `txnDate` | number | no | Epoch millis. Defaults to now when omitted, null, or `0`. |
| `payeeName` | string | no | Max 300 chars. Free text — the merchant or payer. Resolved to a payee server-side; you get a `payeeId` back. |
| `note` | string | no | Max 500 chars. |
| `paymentMethod` | string | no | `CASH`, `CARD`, `BANK_TRANSFER`, `WALLET`, or `OTHER`. Omit it when the user did not pick one — it is a label on the entry, **not** a choice of account. |
| `tags` | string[] | no | Defaults to `[]`. |

```json
{
  "type": "EXPENSE",
  "categoryId": "c31d0a77-...-uuid",
  "amount": 1050,
  "txnDate": 1755500000000,
  "payeeName": "Corner Cafe",
  "note": "burger",
  "paymentMethod": "CARD",
  "tags": ["lunch"]
}
```

> **`txnDate` carries the user's own clock.** Build it from the date *and* the time the
> picker produced, in the device's timezone, and send the resulting instant — 20/08/2026
> 20:00 in `Asia/Colombo` is `1787236200000`. The server never re-interprets the wall
> clock: it stores the instant, and slices months by it in the user's timezone
> ([§9](#9-complete-onboarding)), which is why that timezone has to be sent at register
> or onboarding.

### Response `data`

The created [transaction object](#the-transaction-object).

### Errors

- `400 E1015` — `type` missing, `categoryId` blank, `amount` not positive, or `note`/`payeeName` over length.
- `400 E1013` — `paymentMethod` is not one of the five values (a JSON body the server cannot read is a bad request, not a validation report, so it has no field list).
- `400 E1013` — the category's kind doesn't match `type` (e.g. an expense category on an `INCOME` transaction).
- `404 E1010` — no category with that id owned by the caller.
- `401` — missing/invalid access token.

---

## 17. List Transactions

```
GET /api/v1/transactions
```
**Auth:** required (`Bearer <accessToken>`)

Returns the caller's transactions, **newest first**. All four filters are optional and
combine freely; sending none returns everything.

### Query parameters

| Param | Type | Notes |
|---|---|---|
| `accountId` | string | Restrict to one account. Omit to span all of the caller's accounts. |
| `type` | string | `INCOME` or `EXPENSE`. Case-insensitive. |
| `startDate` | string | `yyyy-MM-dd`, **inclusive**. |
| `endDate` | string | `yyyy-MM-dd`, **inclusive**. |

```
GET /api/v1/transactions?startDate=2026-08-01&endDate=2026-08-31&type=EXPENSE
```

> **Send plain calendar dates — do not convert to epoch millis.** The server resolves
> them in the user's own timezone ([§9](#9-complete-onboarding)) and does the boundary
> arithmetic itself. Both ends are inclusive, so `endDate=2026-08-31` covers that whole
> day through 23:59:59.999 local. This is deliberate: it is the same boundary rule the
> monthly summary uses, so a transaction at midnight can never be counted by the list
> and the summary differently. A client computing its own instants would reintroduce
> exactly that mismatch.

For a calendar-month view, send that month's first and last day. There is no `month`
shorthand — the date range covers it.

### Response `data`

A JSON array of [transaction objects](#the-transaction-object), newest first.

```json
[
  { "id": "7c4a1b90-...", "type": "EXPENSE", "amount": 1050, "txnDate": 1755500000000, "...": "..." },
  { "id": "2f9d3e11-...", "type": "INCOME",  "amount": 500000, "txnDate": 1755400000000, "...": "..." }
]
```

> **No pagination yet.** The whole filtered result comes back in one array — there is no
> `page`/`size` and no total count. Filter by date range rather than fetching everything
> and paging client-side; a user with a long history will otherwise transfer the lot on
> every call. Pagination will change this response into a page envelope when it lands.

### Errors

- `400 E1013` — unknown `type`, a date not in `yyyy-MM-dd` form, or `startDate` after `endDate`.
- `401` — missing/invalid access token.

An `accountId` that belongs to someone else is not an error — it simply matches nothing,
because the query is scoped to the caller first.

---

## 18. Get One Transaction

```
GET /api/v1/transactions/{id}
```
**Auth:** required (`Bearer <accessToken>`)

### Response `data`

One [transaction object](#the-transaction-object).

### Errors

- `404 E1010` — no transaction with that id owned by the caller.
- `401` — missing/invalid access token.

---

## 19. Update Transaction

```
PUT /api/v1/transactions/{id}
```
**Auth:** required (`Bearer <accessToken>`)

### Request body

Identical to [Create Transaction](#16-create-transaction).

> **This is a full replacement, not a patch.** Every field is re-specified on every
> edit: omitting `note`, `payeeName`, or `tags` **clears** them rather than leaving
> them alone. Send the whole object back, populated from what you fetched.

Moving `txnDate` across a month boundary is allowed and simply re-slices which month
the row counts in — nothing needs recomputing, but two months' totals change, so
refresh the summary after an edit that moves a date.

### Response `data`

The updated [transaction object](#the-transaction-object).

### Errors

Same as [Create Transaction](#16-create-transaction), plus:

- `404 E1010` — no transaction with that id owned by the caller.

---

## 20. Delete Transaction

```
DELETE /api/v1/transactions/{id}
```
**Auth:** required (`Bearer <accessToken>`)

> **A hard delete** — unlike accounts ([§15](#15-delete-account)), the row is removed
> outright. There is no undo and no `DELETED` status to restore from, so confirm in
> the UI before calling.

### Response `data`

```json
{ "message": "Transaction deleted" }
```

### Errors

- `404 E1010` — no transaction with that id owned by the caller.
- `401` — missing/invalid access token.

---

## 21. Monthly Summary

```
GET /api/v1/summary/monthly
```
**Auth:** required (`Bearer <accessToken>`)

The home screen's figures: total income, total expenses, and the position for one
calendar month. Nothing is stored or cached — both totals are summed from the
transaction rows on every call, which is why they can never disagree with the list
below them.

### Query parameters

| Param | Type | Notes |
|---|---|---|
| `month` | string | ISO `yyyy-MM`. Omit for the caller's **current** month, resolved in their timezone. |
| `accountId` | string | Restrict to one account. Omit to span every account the caller holds. |

```
GET /api/v1/summary/monthly?month=2026-08&accountId=9b1e2c44-...
```

### Response `data`

```json
{
  "month": "2026-08",
  "timezone": "Asia/Colombo",
  "from": 1754006400000,
  "to": 1756684800000,
  "income": 500000,
  "expenses": 132050,
  "position": 367950,
  "currency": "LKR",
  "accountId": null
}
```

| Field | Notes |
|---|---|
| `income` / `expenses` | Minor units. Both are positive totals. |
| `position` | `income − expenses`. **Legitimately negative** in a month that ran at a deficit — render a deficit state rather than clamping at zero. It is this month alone: nothing is carried in from last month or forward into next. |
| `from` / `to` | The exact window summed, epoch millis, `from` inclusive and `to` exclusive. |
| `timezone` | The zone the month boundaries were resolved in — the user's, falling back to `UTC`. |
| `accountId` | Echoes the filter; `null` means the figures span every account. |

> **Keep this and the transaction list in step.** If the home screen has an account
> picker, pass the same `accountId` to both — otherwise a filtered feed sits under an
> unfiltered total. To list the rows behind these figures, call
> [List transactions](#17-list-transactions) with that month's first and last day as
> `startDate`/`endDate`; both endpoints resolve month boundaries the same way, so the
> rows always add up to the totals.

An empty month returns zeros, not `null` and not an error — a user with no transactions
yet gets `0` across the board, which is the correct figure to render.

### Errors

- `400 E1013` — `month` isn't in `yyyy-MM` form.
- `404 E1010` — no account with that `accountId` owned by the caller. *(Deliberately not a zeroed summary: `0.00` would be a wrong answer rendered as a fact.)*
- `401` — missing/invalid access token.

---

## 22. Category Breakdown

```
GET /api/v1/summary/breakdown?startDate=2026-08-01&endDate=2026-08-31
```
**Auth:** required (`Bearer <accessToken>`)

The detail behind [§21](#21-monthly-summary): the same income and expense totals for a
period, plus the categories that make each one up. Use it for the reports screen —
a pie or bar chart of where the money went, and the same figures broken out by
income category and expense category.

### Query parameters

| Param | Type | Required | Notes |
|---|---|---|---|
| `startDate` | string | **yes** | `yyyy-MM-dd`, inclusive, in the caller's timezone. |
| `endDate` | string | **yes** | `yyyy-MM-dd`, inclusive, in the caller's timezone. |
| `accountId` | string | no | Restrict to one account. Omit to span every account the caller holds. |

Both dates are required — a report is always over a period. A calendar month is
simply its first and last day; there is no `month` shorthand, and the same range works
for a week, a quarter, or a year.

### Response `data`

```json
{
  "startDate": "2026-08-01",
  "endDate": "2026-08-31",
  "timezone": "Asia/Colombo",
  "from": 1754006400000,
  "to": 1756684800000,
  "currency": "LKR",
  "accountId": null,
  "income": {
    "total": 575000,
    "categories": [
      { "categoryId": "c-salary", "name": "Salary", "parentId": null,
        "color": "#22C55E", "icon": "wallet", "amount": 500000, "transactionCount": 1 },
      { "categoryId": "c-side", "name": "Freelance", "parentId": null,
        "color": "#10B981", "icon": "laptop", "amount": 75000, "transactionCount": 2 }
    ]
  },
  "expenses": {
    "total": 165005,
    "categories": [
      { "categoryId": "c-rent", "name": "Rent", "parentId": null,
        "color": "#EF4444", "icon": "home", "amount": 120000, "transactionCount": 1 },
      { "categoryId": "c-food", "name": "Food", "parentId": null,
        "color": "#F59E0B", "icon": "utensils", "amount": 45005, "transactionCount": 9 }
    ]
  },
  "position": 409995
}
```

| Field | Notes |
|---|---|
| `income.total` / `expenses.total` | Minor units. Each equals the sum of its own `categories[].amount`. |
| `position` | `income.total − expenses.total`. Matches [§21](#21-monthly-summary)'s `position` when the period is exactly one calendar month. |
| `categories[]` | **Sorted biggest amount first** — render in the order given. Empty array when nothing was recorded in that direction. |
| `name` / `color` / `icon` | The category's own display fields, joined server-side so a chart doesn't need a second call to label and colour itself. |
| `parentId` | Non-null for a subcategory. **Categories are listed flat**, so a subcategory appears as its own row, not folded into its parent — group on `parentId` if you want a top-level view with drill-down. |
| `transactionCount` | How many transactions make up that bucket, for a "9 transactions" subtitle. |

> **Percentages are the client's job.** The server returns absolute minor units only. A
> category's share is `amount / section.total` — a proportion isn't money, so it needs no
> minor-unit rounding and the server does not guess how you want it displayed.

Like the monthly summary, nothing here is stored or cached — the buckets are one grouped
aggregate over the same rows [List transactions](#17-list-transactions) returns for the
same dates, so a report can never disagree with the ledger it describes.

### Errors

- `400 E1013` — `startDate` or `endDate` missing, not in `yyyy-MM-dd` form, or `startDate` after `endDate`.
- `404 E1010` — no account with that `accountId` owned by the caller.
- `401` — missing/invalid access token.

---

## Budgets — what the app must know first

The money rules from [§Transactions](#transactions--what-the-app-must-know-first)
carry over unchanged: minor units, always positive. Four more decide whether a
budget screen shows the truth.

**1. A budget names one period, and only that period.** Every budget carries a
`periodKey` — `2026-08` for a `MONTHLY` budget, `2026` for a `YEARLY` one. Food at
$200 in July and $300 in August is **two budgets**, not one budget edited. There is
deliberately no "every month" budget: a month the user never set one for has none,
and a cap created in May never reaches back to January. A "same as last month"
button is a second `POST` with the next `periodKey`, not a flag.

**2. A budget belongs to one account, and counts only that account's spending.**
`accountId` is required, and `spent` sums EXPENSE rows on that account alone — so the
same category budgeted on two accounts reports two different figures.

> **Watch this against the ledger's asymmetry.** A client still cannot choose which
> account a transaction posts to ([§Transactions rule 3](#transactions--what-the-app-must-know-first)) —
> every write lands on the primary account. So a budget on any *other* account will
> sit at `spent: 0` no matter what the user records. Until per-transaction account
> selection exists, budget the primary account.

**3. `spent` and `remaining` are derived on every read**, never stored — same as the
monthly position. They cannot drift from the ledger, and they change the moment a
transaction is added, edited, or deleted, so refresh after any ledger write.

**4. Two kinds of budget, and they must not be added together.** `categoryId` set is
a cap on one EXPENSE category; `categoryId: null` is an **overall** cap on everything
in that account. The overall budget's `spent` already contains every category's spend,
so summing both double-counts the same money. [§25](#25-monthly-budget-summary) does
this split for you.

### The budget object

Every endpoint in this section returns this shape:

```json
{
  "id": "b41c7d02-...-uuid",
  "accountId": "9b1e2c44-...-uuid",
  "categoryId": "c31d0a77-...-uuid",
  "period": "MONTHLY",
  "periodKey": "2026-08",
  "amountLimit": 50000,
  "currency": "USD",
  "rollover": false,
  "status": "ACTIVE",
  "periodStart": 1785522600000,
  "periodEnd": 1788201000000,
  "spent": 27700,
  "remaining": 22300
}
```

| Field | Notes |
|---|---|
| `accountId` | The account this cap applies to. Required on create; never changes. |
| `categoryId` | An EXPENSE category, or `null` for an overall cap. |
| `period` | `MONTHLY` or `YEARLY`. |
| `periodKey` | The single period the cap applies to — `yyyy-MM` for `MONTHLY`, `yyyy` for `YEARLY`. |
| `amountLimit` | Minor units, positive — the cap the user set. |
| `currency` | ISO-4217, **derived from the linked account** on every read; a budget stores no currency of its own. |
| `rollover` | Stored and echoed but **not yet applied** — `remaining` ignores it today. Don't ship UI that promises carry-over. |
| `status` | `ACTIVE`, `ARCHIVED`, or `DELETED`. Delete is soft ([§29](#29-delete-budget)): a deleted budget never appears in a listing, but is still readable by id. |
| `periodStart` / `periodEnd` | The exact window `spent` was summed over, epoch millis, `periodStart` inclusive and `periodEnd` exclusive, resolved in the user's timezone. |
| `spent` | Σ EXPENSE in that window, on that account, in that category (all categories when `categoryId` is null). Positive. |
| `remaining` | `amountLimit − spent`. **Legitimately negative** once the user is over budget — render an over-budget state rather than clamping at zero. |

---

## 23. Create Budget

```
POST /api/v1/budgets
```
**Auth:** required (`Bearer <accessToken>`)

Sets one cap, for one period, on one account. At most one **active** budget per
(`accountId`, `categoryId`, `period`, `periodKey`) — a second one for the same slot
is rejected rather than silently replacing the first.

### Request body

| Field | Type | Required | Notes |
|---|---|---|---|
| `accountId` | string | yes | One of the caller's own accounts ([§11](#11-list-active-accounts)). |
| `categoryId` | string | no | An EXPENSE category. Omit or send `null` for an overall cap. |
| `period` | string | yes | `MONTHLY` or `YEARLY`. |
| `periodKey` | string | yes | `yyyy-MM` when `period` is `MONTHLY`, `yyyy` when `YEARLY`. Must match the period type. |
| `amountLimit` | number | yes | Minor units, **positive**. |
| `rollover` | boolean | no | Defaults `false`. Stored, not yet applied. |

`currency` is not accepted — it comes from the account.

```json
{
  "accountId": "9b1e2c44-...-uuid",
  "categoryId": "c31d0a77-...-uuid",
  "period": "MONTHLY",
  "periodKey": "2026-08",
  "amountLimit": 50000,
  "rollover": false
}
```

An overall cap for the same month, and a yearly cap:

```json
{ "accountId": "9b1e2c44-...", "period": "MONTHLY", "periodKey": "2026-08", "amountLimit": 300000 }
{ "accountId": "9b1e2c44-...", "period": "YEARLY",  "periodKey": "2026",    "amountLimit": 3600000 }
```

### Response `data`

The created [budget object](#the-budget-object) — already carrying `spent` for that
period, so a budget created mid-month shows the spending that already happened.

### Errors

- `400 E1015` — `accountId` blank, `period` missing, `periodKey` blank, or `amountLimit` not positive.
- `400 E1013` — `periodKey` doesn't match the period type (`"periodKey must be yyyy-MM for a MONTHLY budget, e.g. 2026-08."`), the category isn't an EXPENSE category, the account is deleted, or an active budget already exists for that account + category + period.
- `404 E1010` — no account, or no category, with that id owned by the caller.
- `401` — missing/invalid access token.

---

## 24. List Budgets

```
GET /api/v1/budgets
```
**Auth:** required (`Bearer <accessToken>`)

Every budget the caller has, across all periods and accounts.

### Query parameters

| Param | Type | Notes |
|---|---|---|
| `includeArchived` | boolean | Defaults `false` — archived budgets are hidden. **Deleted budgets are never returned**, whatever this is set to. |

### Response `data`

A JSON array of [budget objects](#the-budget-object).

> **This is not a month view.** Because each month is its own row, this list grows by
> one entry per budget per month and mixes `MONTHLY` with `YEARLY` rows. For a budget
> screen showing "this month", call [§25](#25-monthly-budget-summary) instead — it
> filters to the month and does the totalling. There is no `period` filter here yet.

### Errors

- `401` — missing/invalid access token.

---

## 25. Monthly Budget Summary

```
GET /api/v1/budgets/summary
```
**Auth:** required (`Bearer <accessToken>`)

The budget screen in one call: the caps the user set for a month, what has been
spent against each, and how the month's actual spending compares.

### Query parameters

| Param | Type | Notes |
|---|---|---|
| `month` | string | ISO `yyyy-MM`. Omit for the caller's **current** month, resolved in their timezone. |

```
GET /api/v1/budgets/summary?month=2026-08
```

### Response `data`

```json
{
  "month": "2026-08",
  "timezone": "Asia/Colombo",
  "from": 1785522600000,
  "to": 1788201000000,
  "currency": "LKR",
  "totalLimit": 80000,
  "totalSpent": 27700,
  "totalRemaining": 52300,
  "monthExpenses": 264190,
  "budgets": [ { "...": "budget objects" } ]
}
```

| Field | Notes |
|---|---|
| `totalLimit` / `totalSpent` | **Category budgets only** — the overall cap is listed in `budgets` but excluded from the totals, because its spend already contains every category's (rule 4 above). |
| `totalRemaining` | `totalLimit − totalSpent`. Negative once the month's category budgets are collectively overspent. |
| `monthExpenses` | Every EXPENSE recorded that month, **across all accounts, budgeted or not** — the honest denominator for "how much of my spending is actually planned". Compare it with `totalSpent`; the gap is unbudgeted spending. |
| `budgets` | The month's `ACTIVE` budgets as [budget objects](#the-budget-object), each with its own limit, spend and window. |
| `from` / `to` | The month window, epoch millis, `from` inclusive and `to` exclusive. |
| `currency` | The caller's active currency. |

> **`MONTHLY` budgets only.** A `YEARLY` cap covers a different window, and folding a
> 3,600,000 annual limit into a month's total would misstate both. Read yearly budgets
> through [§24](#24-list-budgets) / [§26](#26-get-one-budget) and show them separately.
>
> Archived and deleted budgets are excluded — a summary is what the user is planning against now.

A month with no budgets returns zeros and an empty `budgets` array, not an error —
`monthExpenses` is still populated, which is the right thing to show on an empty
budget screen ("you spent X this month, set a budget?").

### Errors

- `400 E1013` — `month` isn't in `yyyy-MM` form.
- `401` — missing/invalid access token.

---

## 26. Get One Budget

```
GET /api/v1/budgets/{id}
```
**Auth:** required (`Bearer <accessToken>`)

### Response `data`

One [budget object](#the-budget-object), with `spent` recomputed for its own period.

### Errors

- `404 E1010` — no budget with that id owned by the caller.
- `401` — missing/invalid access token.

---

## 27. Update Budget

```
PUT /api/v1/budgets/{id}
```
**Auth:** required (`Bearer <accessToken>`)

### Request body

| Field | Type | Required | Notes |
|---|---|---|---|
| `amountLimit` | number | no | Minor units, positive. |
| `rollover` | boolean | no | Stored, not yet applied. |

```json
{ "amountLimit": 80000, "rollover": false }
```

> **A partial update, not a replacement** — the opposite of
> [Update transaction](#19-update-transaction). A field left out or sent as `null` is
> left unchanged, so sending only `amountLimit` is the normal case.
>
> **`accountId`, `categoryId`, `period` and `periodKey` are the budget's identity and
> cannot be edited.** Raising August's food cap edits August's budget and leaves July's
> alone; giving September a different cap is a `POST` with `periodKey: "2026-09"`, not
> an edit.

### Response `data`

The updated [budget object](#the-budget-object).

### Errors

- `400 E1015` — `amountLimit` not positive (`"amountLimit: must be greater than 0"`).
- `404 E1010` — no budget with that id owned by the caller.
- `401` — missing/invalid access token.

---

## 28. Archive Budget

```
POST /api/v1/budgets/{id}/archive
```
**Auth:** required (`Bearer <accessToken>`)

Retires a budget without losing it: `status` → `ARCHIVED`. It disappears from
[§24](#24-list-budgets) unless `includeArchived=true`, drops out of
[§25](#25-monthly-budget-summary), and **frees its slot** so a new active budget can be
created for the same account + category + period.

Use it when the user is retiring a plan they may still want to see; use
[§29](#29-delete-budget) when they want it off the screen for good. Both keep the row
and both free the slot — the difference is visibility: an archived budget still comes
back with `includeArchived=true`, a deleted one never does. There is **no un-archive
endpoint yet** — an archived budget can only be replaced by a new one.

### Response `data`

The archived [budget object](#the-budget-object), `status: "ARCHIVED"`.

### Errors

- `404 E1010` — no budget with that id owned by the caller.
- `401` — missing/invalid access token.

---

## 29. Delete Budget

```
DELETE /api/v1/budgets/{id}
```
**Auth:** required (`Bearer <accessToken>`)

> **A soft delete** — unlike [Delete transaction](#20-delete-transaction), the row is
> kept and its `status` becomes `DELETED`. It leaves every listing
> ([§24](#24-list-budgets), `includeArchived=true` included) and every summary, and can
> no longer be edited or archived, but it stays readable by id. Nothing references a
> budget, so **no transaction is affected**: deleting a budget removes a plan, never any
> recorded money.
>
> The row surviving is for history, not for undo — there is **no restore endpoint**, so
> treat this as final in the UI and confirm before calling. Prefer
> [§28](#28-archive-budget) when the user may want to see the budget again.

Deleting frees the (`accountId`, `categoryId`, `period`, `periodKey`) slot, so a new
budget can immediately be created for the same month and category.

### Response `data`

```json
{ "message": "Budget deleted" }
```

### Errors

- `400 E1013` — the budget is already deleted.
- `404 E1010` — no budget with that id owned by the caller.
- `401` — missing/invalid access token.

---

## Categories — what the app must know first

**1. Every transaction needs one, and the kinds must match.** A category is either
`INCOME` or `EXPENSE`, and an `INCOME` transaction requires an income category —
mismatching them is a `400`, not a silent coercion. So a category picker must filter
by the direction the user is entering.

**2. The user already has 16.** [Onboarding](#9-complete-onboarding) seeds a full set,
so the ledger works before this API is ever called. Nothing here is needed to get a
first transaction recorded.

**3. Names are unique per kind, compared case-insensitively.** `Food`, `food` and
`FOOD` are one category, and a second one is refused. The same name in the *other*
kind is allowed and often correct — "Gifts" received is an income category, "Gifts"
given is an expense one, and they can never be confused because a picker only ever
shows one kind.

**4. One level of hierarchy, fixed at creation.** A category may have a `parentId`,
but a child cannot itself be a parent, and it must share its parent's `kind`. Neither
`kind` nor `parentId` can be changed afterwards — to re-file a category, create the
one you want.

**5. Delete is soft, and that is what makes it usable.** A category with months of
transactions behind it can still be deleted: the row is kept, so
[past reports keep naming it](#22-category-breakdown), while the category leaves every
picker and can no longer be chosen for anything new. Its name is freed for reuse.

### The category object

Every endpoint in this section returns this shape:

```json
{
  "id": "c31d0a77-...-uuid",
  "name": "Food & Drinks",
  "kind": "EXPENSE",
  "parentId": null,
  "color": "#F59E0B",
  "icon": "utensils",
  "sortOrder": 0,
  "status": "ACTIVE"
}
```

| Field | Notes |
|---|---|
| `name` | Trimmed server-side. Max 200 chars. Unique per (user, `kind`), case-insensitively. |
| `kind` | `INCOME` or `EXPENSE`. Fixed at creation. |
| `parentId` | `null` for a top-level category; otherwise its parent, which is always top-level and the same `kind`. Fixed at creation. |
| `color` / `icon` | Optional display hints the client chooses the meaning of — the server stores and returns them untouched (max 20 / 50 chars). Both are `null` on every seeded category, so the app needs its own fallbacks. |
| `sortOrder` | Client-chosen ordering within a kind. Defaults `0`; the seeded set numbers each kind from `0`. |
| `status` | `ACTIVE` or `DELETED`. Delete is soft ([§34](#34-delete-category)): a deleted category never appears in a listing and cannot be chosen, but stays readable by id so old records can still be labelled. |

> **`color` and `icon` cannot be cleared once set.** [Update](#33-update-category)
> treats `null` as "leave unchanged", so there is no way to unset one — sending
> `"color": null` keeps the old value. Send a new value to change it, and expect to
> keep whatever was set last.

---

## 30. List Categories

```
GET /api/v1/categories
```
**Auth:** required (`Bearer <accessToken>`)

Every live category the caller holds — the picker's data source.

### Response `data`

A JSON array of [category objects](#the-category-object), sorted **`kind`, then
`sortOrder`, then `name`** — render in the order given.

```json
[
  { "id": "c-salary", "name": "Salary", "kind": "INCOME", "parentId": null,
    "color": null, "icon": null, "sortOrder": 0, "status": "ACTIVE" },
  { "id": "c-food", "name": "Food & Drinks", "kind": "EXPENSE", "parentId": null,
    "color": null, "icon": null, "sortOrder": 0, "status": "ACTIVE" }
]
```

> **Flat, not nested, and live only.** Sub-categories come back as their own rows —
> group on `parentId` if the UI wants a tree. Deleted categories are never returned,
> and there is no `includeDeleted` flag; to resolve one that an old transaction still
> points at, fetch it by id ([§32](#32-get-one-category)).

There is no filter for `kind` — the list is small (16 by default), so filter client-side
and the picker gets both directions from one call.

### Errors

- `401 E1060` / `E1061` / `E1062` — no token, an invalid one, or an expired one.

---

## 31. Create Category

```
POST /api/v1/categories
```
**Auth:** required (`Bearer <accessToken>`)

### Request body

| Field | Type | Required | Notes |
|---|---|---|---|
| `name` | string | yes | Non-blank, max 200 chars. Trimmed, then checked for a case-insensitive clash within the same `kind`. |
| `kind` | string | yes | `INCOME` or `EXPENSE`. |
| `parentId` | string | no | Makes this a sub-category. Must be one of the caller's own **live, top-level** categories of the **same kind**. |
| `color` | string | no | Max 20 chars. |
| `icon` | string | no | Max 50 chars. |
| `sortOrder` | number | no | Defaults `0`. |

```json
{
  "name": "Coffee",
  "kind": "EXPENSE",
  "parentId": "c-food",
  "color": "#F59E0B",
  "icon": "coffee",
  "sortOrder": 3
}
```

### Response `data`

The created [category object](#the-category-object).

### Errors

- `400 E1015` — `name` blank or over 200 chars, `kind` missing, or `color`/`icon` over length.
- `400 E1013` — a live category of this kind already holds that name (`"A category named 'FOOD' already exists."`), the parent already has a parent (`"Sub-categories are only one level deep."`), the parent's kind differs, or the parent is deleted.
- `404 E1010` — no category with that `parentId` owned by the caller.
- `400 E1013` — `kind` present but not `INCOME`/`EXPENSE`. See the [unreadable-body note](#generic-outcomes) — the field is not named in the message, so send a valid value.
- `401` — missing/invalid access token.

---

## 32. Get One Category

```
GET /api/v1/categories/{id}
```
**Auth:** required (`Bearer <accessToken>`)

### Response `data`

One [category object](#the-category-object).

> **This is the one endpoint that returns deleted categories** — check `status` before
> offering it as a choice. It exists so a client holding an old `categoryId` (from a
> cached transaction, say) can still resolve a name to display.

### Errors

- `404 E1010` — no category with that id owned by the caller.
- `401` — missing/invalid access token.

---

## 33. Update Category

```
PUT /api/v1/categories/{id}
```
**Auth:** required (`Bearer <accessToken>`)

### Request body

| Field | Type | Required | Notes |
|---|---|---|---|
| `name` | string | no | Max 200 chars. Must not clash, case-insensitively, with another live category of the same kind. |
| `color` | string | no | Max 20 chars. |
| `icon` | string | no | Max 50 chars. |
| `sortOrder` | number | no | |

```json
{ "name": "Food & Drink", "color": "#EF4444", "sortOrder": 1 }
```

> **A partial update** — like [Update budget](#27-update-budget) and unlike
> [Update transaction](#19-update-transaction). A field left out or sent as `null` is
> left unchanged; a blank `name` is ignored rather than rejected.
>
> **`kind` and `parentId` are not editable**, and a deleted category cannot be edited
> at all. Recasing a category's own name (`food` → `Food`) is fine — it only clashes
> with *other* categories.

### Response `data`

The updated [category object](#the-category-object).

### Errors

- `400 E1015` — a field is over length.
- `400 E1013` — another live category of this kind already holds that name, or this category is deleted (`"Category is deleted."`).
- `404 E1010` — no category with that id owned by the caller.
- `401` — missing/invalid access token.

---

## 34. Delete Category

```
DELETE /api/v1/categories/{id}
```
**Auth:** required (`Bearer <accessToken>`)

> **A soft delete** — `status` becomes `DELETED` and the row is kept, like
> [Delete budget](#29-delete-budget) and unlike
> [Delete transaction](#20-delete-transaction). **Transactions already filed under it
> are untouched and keep pointing at it**, which is the whole point: a category with
> months of history behind it stays deletable, and
> [Category breakdown](#22-category-breakdown) still names it in those months.
>
> What changes is availability: it leaves [§30](#30-list-categories), cannot be chosen
> for a new transaction, recurring template or budget (those answer
> `404 E1010`), cannot be edited, and frees its name for reuse. There is **no restore
> endpoint**, so treat it as final in the UI.

Refused while it would leave something dangling:

| Refused when | Why |
|---|---|
| it has a live sub-category | the child would be orphaned — delete or re-file the children first |
| a live budget targets it | the budget would go on measuring spend against a category nothing can be filed under. Delete or archive the budget first ([§29](#29-delete-budget) / [§28](#28-archive-budget)) |

Existing **transactions never block it** — that is the difference from the old
behaviour, where a category used even once could not be removed.

### Response `data`

```json
{ "message": "Category deleted" }
```

### Errors

- `400 E1013` — already deleted (`"Category already deleted."`), has live sub-categories, or a live budget targets it.
- `404 E1010` — no category with that id owned by the caller.
- `401` — missing/invalid access token.

---

## 35. Seed Default Categories

```
POST /api/v1/categories/seed-defaults
```
**Auth:** required (`Bearer <accessToken>`)

Provisions the default set. [Onboarding](#9-complete-onboarding) already calls this, so
a normal client never needs to — it exists for a user who has ended up with no
categories at all.

**Idempotent, and specifically: it does nothing if the caller has any live category**,
returning the existing list untouched rather than duplicating it. It *will* seed again
for a user who has deleted every category, since the check is on live rows.

### Response `data`

The caller's full category list, same shape and ordering as [§30](#30-list-categories) —
either the 16 just created or the ones they already had.

| Kind | Names |
|---|---|
| `INCOME` | Salary, Business, Freelance, Investments, Gifts |
| `EXPENSE` | Food & Drinks, Groceries, Transport, Housing, Utilities, Entertainment, Health, Shopping, Education, Subscriptions, Other |

`sortOrder` runs from `0` within each kind, in the order listed. These are ordinary
user-owned rows — fully renameable and deletable, not a system table.

### Errors

- `401` — missing/invalid access token.

---

## Recurring & subscriptions — what the app must know first

A recurring template is a **rule**, not a transaction: *£9.99 to Spotify, monthly,
next on the 24th*. A background job turns it into real ledger rows on their due
dates, and those rows are ordinary transactions — same shape, same totals, with
`recurringId` pointing back at the template that produced them.

**1. A subscription is a recurring expense.** There is no separate subscription
endpoint or entity. Netflix, the gym, and the rent are all one template with a
`cadence`; what makes a subscription a subscription is that it may carry a
`trialEndDate`. The entry screen's "Billing Cycle" maps straight onto `cadence`.

**2. "One-time" is not a cadence — it is a different endpoint.** If the user leaves
Billing Cycle on *One-time*, post to [Create Transaction](#16-create-transaction).
Anything else posts here. Build one payload object in the client and pick the URL
from that field; the two bodies deliberately share their field names.

**3. Nothing is posted before its date.** A template does not write anything into
the ledger until the due date arrives, so it changes no month's position until then.
The payments the user is *about* to make come from [Upcoming
payments](#38-upcoming-payments) — a projection, not rows.

**4. The account and currency are not yours to send**, exactly as for a transaction
([§Transactions](#transactions--what-the-app-must-know-first)). The currency you get
back on a template is read off its account on every request, not stored against the
template — one less thing that can disagree with itself. The transactions it generates
each keep their own `currency`, because a ledger row records what the money *was*.

### The recurring template object

```json
{
  "id": "b7d81c02-...-uuid",
  "categoryId": "c31d0a77-...-uuid",
  "type": "EXPENSE",
  "amount": 999,
  "currency": "USD",
  "cadence": "MONTHLY",
  "nextRunDate": 1787000000000,
  "anchorDay": 24,
  "trialEndDate": null,
  "endDate": null,
  "active": true,
  "payeeId": "p88f2e10-...-uuid",
  "note": "Spotify",
  "paymentMethod": "CARD"
}
```

| Field | Notes |
|---|---|
| `cadence` | `DAILY`, `WEEKLY`, `MONTHLY`, `YEARLY`. The repeat or billing frequency. |
| `currency` | ISO-4217, **resolved from the account** the template posts to rather than stored on it — so it always agrees with that account and can never go stale. Returned on every read; never sent. |
| `nextRunDate` | Epoch millis of the next due or renewal date. Advances past each generated row. |
| `anchorDay` | Day-of-month the MONTHLY/YEARLY cycle is pinned to, **read in the user's timezone**. A 31st template clamps to a short month's last day and then returns to the 31st, so it never drifts to the 28th permanently. Server-derived from `nextRunDate` — you never send it. |
| `trialEndDate` | Epoch millis; free-trial end. `null` for anything without a trial. |
| `endDate` | Epoch millis; generation stops once the next run would pass it, and `active` flips to `false`. |
| `active` | `false` means paused (or finished). A paused template generates nothing and appears in no upcoming list. |
| `paymentMethod` | As on a transaction, and **copied onto every row this template generates**. |

---

## 36. Create Recurring Template

```
POST /api/v1/recurring
```
**Auth:** required (`Bearer <accessToken>`)

### Request body

| Field | Type | Required | Notes |
|---|---|---|---|
| `type` | string | yes | `INCOME` or `EXPENSE`. |
| `categoryId` | string | yes | The caller's own category; its kind must match `type`. |
| `amount` | number | yes | Minor units, **positive**. The subscription's cost. |
| `cadence` | string | yes | `DAILY`, `WEEKLY`, `MONTHLY`, `YEARLY`. |
| `nextRunDate` | number | yes | Epoch millis of the **first** run. Its day-of-month, read in the user's timezone, anchors MONTHLY/YEARLY cycles. |
| `trialEndDate` | number | no | Epoch millis; free-trial end. |
| `endDate` | number | no | Epoch millis; stop generating once the next run passes it. |
| `payeeName` | string | no | Max 300 chars. Resolved to a payee and copied onto generated rows. |
| `note` | string | no | Max 500 chars. Copied onto generated rows. |
| `paymentMethod` | string | no | `CASH`, `CARD`, `BANK_TRANSFER`, `WALLET`, `OTHER`. Copied onto generated rows. |

```json
{
  "type": "EXPENSE",
  "categoryId": "c31d0a77-...-uuid",
  "amount": 999,
  "cadence": "MONTHLY",
  "nextRunDate": 1787236200000,
  "payeeName": "Spotify",
  "note": "Spotify Premium",
  "paymentMethod": "CARD"
}
```

> Build `nextRunDate` the same way as `txnDate` — the local date *and* time the user
> picked, converted to an instant in the device's timezone. It is what pins the billing
> day, so an off-by-one here bills the user in the wrong month.

### Response `data`

```json
{
  "template": { "id": "b7d81c02-...", "...": "..." },
  "posted":   null
}
```

| Field | Notes |
|---|---|
| `template` | The created [recurring template object](#the-recurring-template-object). |
| `posted` | Normally `null`. Non-null when `nextRunDate` had **already arrived**: the server posts that one occurrence in the same request and returns it as a [transaction object](#the-transaction-object), so a subscription added on its billing day is in the ledger immediately. |

> **`posted` is at most one row, even for a badly backdated `nextRunDate`.** A template
> dated a year ago has a year of catch-up owed to it; the request posts the first
> occurrence and the background job works through the rest. If `posted` is non-null,
> refresh the ledger, the monthly summary and any budget on screen — real money moved.

### Errors

- `400 E1015` — `type`, `categoryId`, `cadence` or `nextRunDate` missing, or `amount` not positive.
- `400 E1013` — the category's kind doesn't match `type`, or `cadence`/`paymentMethod` is not a known value.
- `404 E1010` — no category with that id owned by the caller.
- `401` — missing/invalid access token.

---

## 37. List Recurring Templates

```
GET /api/v1/recurring
```
**Auth:** required (`Bearer <accessToken>`)

Returns the caller's templates ordered by **`nextRunDate` ascending** — soonest
first, which is the order a "your commitments" screen wants.

### Query parameters

| Param | Type | Notes |
|---|---|---|
| `includeInactive` | boolean | Default `false`. `true` also returns paused and finished templates. |

### Response `data`

A JSON array of [recurring template objects](#the-recurring-template-object).

### Errors

- `401` — missing/invalid access token.

---

## 38. Upcoming Payments

```
GET /api/v1/recurring/upcoming
```
**Auth:** required (`Bearer <accessToken>`)

Every bill, renewal and salary falling due in the next few days, **projected from
the templates**. This is what feeds an "upcoming" strip on the home or entry screen.

### Query parameters

| Param | Type | Notes |
|---|---|---|
| `withinDays` | number | `1`–`90`. Default `3`. |

```
GET /api/v1/recurring/upcoming?withinDays=7
```

> **These are not transactions and they have no `id`.** Nothing has been written: each
> entry is computed from its template on every call, and it is counted by **no** total —
> not the monthly summary, not a budget, not the category breakdown. That is deliberate,
> and it is why the figure at the top of the screen stays a record of what happened
> rather than of what is expected. Act on an entry through its `recurringId`.

The window runs to the **end** of the target day in the user's timezone, so a renewal
on the 24th appears on the 21st with `withinDays=3` whatever time of day it falls at.

### Response `data`

```json
[
  {
    "recurringId": "b7d81c02-...-uuid",
    "type": "EXPENSE",
    "categoryId": "c31d0a77-...-uuid",
    "amount": 999,
    "currency": "USD",
    "cadence": "MONTHLY",
    "dueDate": 1787236200000,
    "due": false,
    "payeeId": "p88f2e10-...-uuid",
    "note": "Spotify",
    "paymentMethod": "CARD",
    "trialEndDate": null,
    "trialEnding": false
  }
]
```

Sorted by `dueDate` ascending, across every active template. A `DAILY` template
contributes one entry per day in the window (capped at 60 per template).

| Field | Notes |
|---|---|
| `recurringId` | The template this came from — the id to open, edit or pause. |
| `dueDate` | Epoch millis this occurrence falls due. |
| `due` | `true` once `dueDate` has passed and the row has **not been posted yet**; the generation job posts it within ~15 minutes. Render both states as pending — the user has not paid either of them. |
| `trialEnding` | `true` when the template's free trial ends inside the requested window. Worth surfacing: it is the last chance to cancel before the first charge. |

**When it disappears:** an entry leaves this list as the job posts it, and the same
money appears in the ledger as a transaction with `recurringId` set. Refresh both
together, and de-duplicate on `recurringId` + date if you cache the list, so the same
charge is never drawn twice during that handover.

### Errors

- `400 E1013` — `withinDays` below 1 or above 90.
- `401` — missing/invalid access token.

---

## 39. Get One Recurring Template

```
GET /api/v1/recurring/{id}
```
**Auth:** required (`Bearer <accessToken>`)

### Response `data`

One [recurring template object](#the-recurring-template-object).

### Errors

- `404 E1010` — no template with that id owned by the caller.
- `401` — missing/invalid access token.

---

## 40. Update Recurring Template

```
PUT /api/v1/recurring/{id}
```
**Auth:** required (`Bearer <accessToken>`)

### Request body

**A patch, not a replacement** — the opposite of [Update
Transaction](#19-update-transaction). Every field is optional and a field you omit is
left alone.

| Field | Type | Notes |
|---|---|---|
| `amount` | number | Minor units, positive. |
| `nextRunDate` | number | Epoch millis. **Reschedules and re-anchors** the MONTHLY/YEARLY day-of-month. |
| `trialEndDate` | number | Epoch millis. |
| `endDate` | number | Epoch millis. |
| `active` | boolean | `false` pauses generation, `true` resumes it. |
| `payeeName` | string | Max 300 chars. |
| `note` | string | Max 500 chars. |
| `paymentMethod` | string | One of the five values. |

> **`type`, `categoryId` and `cadence` are the template's identity and cannot be
> changed here** — they are what its already-generated rows were filed under. To change
> one, pause or delete this template and create a new one.
>
> **Pause, don't delete, to stop a subscription.** `active: false` keeps the history
> readable; deleting removes the template while leaving its rows behind with a
> `recurringId` that no longer resolves.

### Response `data`

The updated [recurring template object](#the-recurring-template-object).

### Errors

- `400 E1013` — `amount` not positive, or `nextRunDate` not a positive epoch-millis value.
- `404 E1010` — no template with that id owned by the caller.
- `401` — missing/invalid access token.

---

## 41. Delete Recurring Template

```
DELETE /api/v1/recurring/{id}
```
**Auth:** required (`Bearer <accessToken>`)

**A hard delete, and it is not a soft one like an account or a category.** The
template row is gone. Transactions it already generated are real money and are left
untouched; their `recurringId` becomes a historical label pointing at nothing.
Prefer `active: false` ([§40](#40-update-recurring-template)) unless the user means
"this was a mistake, forget it".

### Response `data`

```json
{ "message": "Recurring template deleted" }
```

### Errors

- `404 E1010` — no template with that id owned by the caller.
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

Once the home screen is up, the ledger (§§15–19) is the app's steady state: list the
current month's transactions for the feed, and [Monthly summary](#21-monthly-summary)
for the figure at the top of it. The reports screen adds
[Category breakdown](#22-category-breakdown) over whatever period the user picks. All
three read the same rows, so they agree by construction — refresh them together after
any write, and pass the same `accountId` to all of them when an account picker is on
screen.

Managing categories (§§29–34) is settings-time work like accounts: the seeded set
covers first run, and [List categories](#30-list-categories) is what every category
picker reads. Refresh that list after a create, rename or delete, since all three
change what the picker may offer.

The entry screen writes to one of two endpoints depending on its Billing Cycle field:
*One-time* goes to [Create transaction](#16-create-transaction), anything else to
[Create recurring template](#36-create-recurring-template) (§§35–40 are that screen's
full surface). Read [Upcoming payments](#38-upcoming-payments) for the "you are about to
pay" strip — poll it alongside the ledger, since an entry moves from that list into the
ledger when its due date passes, and refresh both after any recurring write.

The budget screen (§§22–28) sits beside the reports one: read it with
[Monthly budget summary](#25-monthly-budget-summary) for whichever month the user is
viewing, and refresh it after **any** ledger write — `spent` is derived from the same
transaction rows, so adding an expense moves a budget immediately. Setting a cap for a
new month is always a create, never an edit: one `POST` per month, with that month's
`periodKey`.
