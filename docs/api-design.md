# GSTBridge: API Design Document

**Version:** 1.0 | **Author:** Amandeep Kumar | **Date:** 4 Oct 2026 | **Stage:** 3 of 7 (API design)
**Based on:** GSTBridge PRD v1.2 and System Design Document v1.2

---

## 1. Purpose and scope

This document defines the HTTP API between the React frontend and the Spring Boot backend: every endpoint, who may call it, what it accepts, what it returns, and which errors it can produce. The PRD defines **what** the product does, the design document defines **how** it is built, and this document is the **contract** both sides code against.

Out of scope: task breakdown (Stage 4), code and tests (Stage 5), hosting (Stage 6).

## 2. Conventions

### 2.1 Transport and versioning

- Every endpoint lives under `/api`. HTTPS in deployment.
- Requests and responses are JSON (UTF-8), except the PDF and CSV downloads.
- There is no `/v1` prefix. Changes must be additive (new fields, new endpoints). If a breaking change is ever needed, a new version is introduced then.

### 2.2 Authentication

- Protected endpoints need `Authorization: Bearer <jwt>`. Missing or invalid token: `401`. Authenticated but not allowed: `403`.
- On every request the server loads the caller's membership by the token's user id and business id, rejects a missing or inactive membership, and takes the role from that **row**. The role inside the token is informational only.
- **`businessId` is never accepted from the client** (not in the path, query or body). It always comes from the token. A record of another business returns `404`.
- Public endpoints: register, login, accept-invitation, health and API docs (section 11).

### 2.3 Naming

- Plural lowercase nouns: `/api/invoices/42`.
- State changes with rules are `POST` sub-paths: `/issue`, `/pay`, `/cancel`, `/activate`, `/deactivate`, `/revoke`, `/calculate`.
- JSON fields are camelCase.

### 2.4 Data formats

| Kind | Format | Example |
| --- | --- | --- |
| Money | String, 2 decimals | `"1180.00"` |
| Quantity | String, 3 decimals | `"2.000"` |
| Percentages (discount, tax rate) | String, 2 decimals | `"18.00"` |
| Counts | JSON number | `14` |
| Dates | `yyyy-MM-dd` | `"2026-10-04"` |
| Timestamps | ISO-8601 UTC | `"2026-10-04T10:15:30Z"` |
| Month | `yyyy-MM` | `"2026-10"` |

Input precision limits: quantity at most 3 decimals; price, discount percent and tax percent at most 2. Excess precision, negatives, exponents and non-numeric text return `400`. Responses always normalize to the formats above.

### 2.5 Request rules

1. `PUT` replaces all editable fields. Required fields must be present and non-blank. For optional fields, **omitted and `null` mean the same thing: the value is cleared**. There is no `PATCH` in v1.
2. String inputs are trimmed. A blank optional string is stored as `null` (never an empty string).
3. **Strict parsing:** unknown fields and read-only fields (totals, `status`, `invoiceNumber`, `id`, `customerType`, `stateName`, `identityLocked`, and so on) return `400 VALIDATION_FAILED`. The server never trusts client-sent amounts.
4. The only response-derived field a request may carry is `version`, and only on `PUT /api/invoices/{id}`.

### 2.6 Pagination

Paginated lists use `page` (zero-based, default `0`) and `size` (default `20`, allowed `1` to `100`). A non-numeric or negative `page`, or a `size` outside 1 to 100, returns `400 VALIDATION_FAILED` (no silent clamping). Response envelope:

```json
{ "content": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0 }
```

Small, bounded lists (states, tax rates, members, invitations, audit history) return a plain JSON array.

### 2.7 Errors

One format for every endpoint, including `401` and `403`:

```json
{
  "timestamp": "2026-10-04T10:15:30Z",
  "status": 422,
  "code": "INVALID_STATUS_TRANSITION",
  "message": "Only draft invoices can be issued",
  "fieldErrors": [],
  "path": "/api/invoices/42/issue"
}
```

`fieldErrors` entries look like `{ "field": "lines[0].quantity", "message": "must be greater than 0" }`. Field paths are full paths such as `business.gstin`.

| Status | Use |
| --- | --- |
| 400 | Malformed or invalid input (shape, precision, unknown fields, bad filters or paging) |
| 401 | Not authenticated |
| 403 | Wrong role |
| 404 | Not found, including records of another business |
| 409 | Conflict: duplicates, stale version |
| 422 | Valid request that breaks a business rule |
| 500 | Unexpected error: generic message, no stack trace |

The full code catalog is in section 12.

### 2.8 Roles

Owner can do everything. Accountant can read and export only: every `GET` listed below is open to both roles unless stated, and every write is Owner-only (`403` for an Accountant).

### 2.9 Downloads (PDF and CSV)

The token travels in the `Authorization` header, which a browser link cannot send. **The frontend must fetch downloads with the token and then save or open the result.** Plain `<a href>` links will not work.

### 2.10 Concurrency and retries

- Draft invoices use optimistic locking: `PUT` must send the current `version`; a stale one returns `409 STALE_VERSION`.
- Issue, pay and cancel take the invoice row lock first (`SELECT ... FOR UPDATE`), then check status, so concurrent requests cannot both succeed.
- Only **issue** is idempotent. Pay and cancel on the wrong status return `422`; a client unsure whether a request landed should `GET` the invoice.
- Customers, items and the business profile have no version: last write wins, and concurrent edits can overwrite each other.

## 3. Endpoint overview

40 application endpoints plus 3 operational ones.

| # | Method and path | Who | Purpose |
| --- | --- | --- | --- |
| 1 | `POST /api/auth/register` | Public | Create user, business and Owner membership |
| 2 | `POST /api/auth/login` | Public | Log in, returns token |
| 3 | `POST /api/auth/accept-invitation` | Public | Accept an Accountant invitation |
| 4 | `GET /api/auth/me` | Any logged in | Current user, business, role |
| 5 | `POST /api/invitations` | Owner | Invite an Accountant (Should) |
| 6 | `GET /api/invitations` | Owner | List invitations |
| 7 | `POST /api/invitations/{id}/revoke` | Owner | Revoke a pending invitation |
| 8 | `GET /api/members` | Owner | List members |
| 9 | `POST /api/members/{membershipId}/deactivate` | Owner | Revoke an Accountant's access |
| 10 | `GET /api/business` | Owner, Accountant | Read business profile |
| 11 | `PUT /api/business` | Owner | Update business profile |
| 12 | `GET /api/states` | Any logged in | State list |
| 13 | `GET /api/tax-rates` | Any logged in | Active tax rates |
| 14 | `GET /api/customers` | Owner, Accountant | List and search customers |
| 15 | `POST /api/customers` | Owner | Create customer |
| 16 | `GET /api/customers/{id}` | Owner, Accountant | Customer detail |
| 17 | `PUT /api/customers/{id}` | Owner | Replace customer |
| 18 | `POST /api/customers/{id}/deactivate` | Owner | Deactivate |
| 19 | `POST /api/customers/{id}/activate` | Owner | Reactivate |
| 20 | `GET /api/items` | Owner, Accountant | List and search items |
| 21 | `POST /api/items` | Owner | Create item |
| 22 | `GET /api/items/{id}` | Owner, Accountant | Item detail |
| 23 | `PUT /api/items/{id}` | Owner | Replace item |
| 24 | `POST /api/items/{id}/deactivate` | Owner | Deactivate |
| 25 | `POST /api/items/{id}/activate` | Owner | Reactivate |
| 26 | `POST /api/invoices` | Owner | Create draft |
| 27 | `GET /api/invoices` | Owner, Accountant | List, search, filter |
| 28 | `GET /api/invoices/{id}` | Owner, Accountant | Invoice detail |
| 29 | `PUT /api/invoices/{id}` | Owner | Replace draft |
| 30 | `DELETE /api/invoices/{id}` | Owner | Delete draft |
| 31 | `POST /api/invoices/calculate` | Owner | Preview amounts for a new draft |
| 32 | `POST /api/invoices/{id}/calculate` | Owner | Preview amounts for an existing draft |
| 33 | `POST /api/invoices/{id}/issue` | Owner | Draft to Issued |
| 34 | `POST /api/invoices/{id}/pay` | Owner | Issued to Paid |
| 35 | `POST /api/invoices/{id}/cancel` | Owner | Issued to Cancelled |
| 36 | `GET /api/invoices/{id}/pdf` | Owner, Accountant | Download PDF |
| 37 | `GET /api/invoices/{id}/audit` | Owner, Accountant | Audit history |
| 38 | `GET /api/reports/monthly-summary` | Owner, Accountant | Monthly totals |
| 39 | `GET /api/reports/monthly-summary/export` | Owner, Accountant | Summary CSV |
| 40 | `GET /api/reports/invoices/export` | Owner, Accountant | Invoice CSV |

## 4. Reference data

**`GET /api/states`** returns all states sorted by code:

```json
[ { "code": "03", "name": "Punjab", "abbreviation": "PB" } ]
```

**`GET /api/tax-rates`** returns active rates only, sorted ascending:

```json
[ { "id": 4, "ratePercent": "18.00" } ]
```

Tax rates are read-only in v1. They change through a Flyway migration or seed, never through the app. A migration that deactivates a rate must first reassign or deactivate every item that uses it.

## 5. Authentication, invitations and members

### 5.1 Register: `POST /api/auth/register`

Creates the user, business and Owner membership in one transaction. The token is issued only after commit.

```json
{
  "email": "ravi@example.com",
  "password": "at-least-8-chars",
  "fullName": "Ravi Sharma",
  "business": {
    "legalName": "Ravi Designs",
    "gstin": "03ABCDE1234F1Z5",
    "stateCode": "03",
    "addressLine": "12 Model Town",
    "city": "Ludhiana",
    "pincode": "141002",
    "invoicePrefix": "INV"
  }
}
```

Response `201` (same shape as login):

```json
{
  "token": "<jwt>",
  "expiresAt": "2026-10-04T18:15:30Z",
  "user": { "id": 7, "email": "ravi@example.com", "fullName": "Ravi Sharma" },
  "business": { "id": 3, "legalName": "Ravi Designs" },
  "role": "OWNER"
}
```

Field rules:

- `email`: stored lowercase, valid format, up to 255 characters.
- `password`: 8 to 72 **UTF-8 bytes** (BCrypt ignores anything beyond 72 bytes).
- `fullName`: required, up to 120 characters.
- `business.*`: see 6.1. `invoicePrefix` is optional here and defaults to `INV`.

Errors: `400 VALIDATION_FAILED`, `409 DUPLICATE_EMAIL`, `409 DUPLICATE_GSTIN`, `422 INVALID_GSTIN`, `422 GSTIN_STATE_MISMATCH`, `422 UNKNOWN_STATE`.

### 5.2 Login: `POST /api/auth/login`

Request `{ "email": "...", "password": "..." }`. Response `200`, same shape as register. The token expires after 8 hours. If the user has several memberships, v1 uses the first active one ordered by `created_at, id`. Errors: `400`, `401 INVALID_CREDENTIALS` (wrong email, wrong password or inactive user; one vague message).

### 5.3 Current user: `GET /api/auth/me`

Read from the database, not the token, so the frontend can show or hide actions correctly after a role change.

```json
{
  "user": { "id": 7, "email": "ravi@example.com", "fullName": "Ravi Sharma" },
  "business": { "id": 3, "legalName": "Ravi Designs" },
  "membershipId": 12,
  "role": "OWNER"
}
```

### 5.4 Invitations

Invitations are a Should-priority feature in the PRD. If time is short, the demo uses a seeded Accountant instead.

**Create: `POST /api/invitations`**, body `{ "email": "ca@example.com" }`. The role is always `ACCOUNTANT` in v1. Response `201`:

```json
{
  "id": 5, "email": "ca@example.com", "role": "ACCOUNTANT",
  "status": "PENDING", "expiresAt": "2026-10-11T10:15:30Z",
  "inviteLink": "https://app.example/accept-invite?token=<raw-token>"
}
```

- The invitation expires after 7 days.
- The link is built from a configured frontend origin.
- The raw token appears **only in this response**. It is stored as a SHA-256 hash and never returned again.
- Errors: `409 INVITATION_ALREADY_PENDING` (one pending invitation per email per business), `409 ALREADY_MEMBER` (that email is already an active member).

**List: `GET /api/invitations`** returns a plain array, newest first, without links or tokens:

```json
[ { "id": 5, "email": "ca@example.com", "role": "ACCOUNTANT", "status": "PENDING",
    "expiresAt": "2026-10-11T10:15:30Z", "acceptedAt": null,
    "invitedBy": { "id": 7, "fullName": "Ravi Sharma" } } ]
```

`status` is one of `PENDING`, `ACCEPTED`, `EXPIRED`, `REVOKED`. A pending invitation whose `expiresAt` has passed is reported as `EXPIRED`.

**Revoke: `POST /api/invitations/{id}/revoke`** returns the invitation with status `REVOKED`. Only a `PENDING` invitation can be revoked (`422 INVITATION_NOT_PENDING`).

**Accept: `POST /api/auth/accept-invitation`**

```json
{ "token": "<raw-token>", "password": "at-least-8-chars", "fullName": "Priya Nair" }
```

- If no account exists for the invited email, one is created (`fullName` required, `400` if missing).
- If an account exists, its password must be correct (`401 INVALID_CREDENTIALS` otherwise); its name and password are never replaced.
- A previously deactivated membership is reactivated.
- The token is consumed atomically and only once.
- Response `200`: the login response of 5.2.
- Errors: `422 INVITATION_INVALID` (one generic message for unknown, expired, revoked, used, or email-mismatch tokens, so tokens cannot be probed).
- **v1 limitation:** an existing user with several memberships gets a login for their earliest active membership, which may not be the invited business. A business switcher is deferred.

### 5.5 Members

**`GET /api/members`** returns a plain array:

```json
[ { "membershipId": 12, "user": { "id": 7, "email": "ravi@example.com", "fullName": "Ravi Sharma" },
    "role": "OWNER", "active": true, "createdAt": "2026-10-01T08:00:00Z" } ]
```

**`POST /api/members/{membershipId}/deactivate`** sets the membership inactive and returns the member entry. Only Accountants can be deactivated (`422 CANNOT_DEACTIVATE_OWNER`). Repeating it returns `200`. Revocation takes effect on the very next request (section 2.2). `membershipId` is the membership row id.

## 6. Business profile

### 6.1 Read and update

**`GET /api/business`**

```json
{
  "id": 3, "legalName": "Ravi Designs", "gstin": "03ABCDE1234F1Z5",
  "stateCode": "03", "stateName": "Punjab",
  "addressLine": "12 Model Town", "city": "Ludhiana", "pincode": "141002",
  "invoicePrefix": "INV", "identityLocked": false
}
```

**`PUT /api/business`** body: `legalName`, `gstin`, `stateCode`, `addressLine`, `city`, `pincode`, `invoicePrefix` (all required). `stateName` and `identityLocked` are read-only and rejected if sent.

Rules:

- `gstin`: trimmed and uppercased, 15 characters, format and checksum validated, unique across businesses.
- `stateCode` must match the first two digits of the GSTIN.
- `invoicePrefix`: 1 to 3 characters, `A-Z` and `0-9` only, auto-uppercased (a `/` would break `PREFIX/2026-27/0001`).
- `pincode`: 6 digits. `legalName` up to 150 characters.
- `identityLocked` becomes `true` once the business has any issued invoice. After that, the GSTIN and prefix may appear in the `PUT` body only with unchanged values; a change returns `422 BUSINESS_IDENTITY_LOCKED`. The lock check is race-safe with invoice issue.

Errors: `400`, `409 DUPLICATE_GSTIN`, `422 INVALID_GSTIN`, `422 GSTIN_STATE_MISMATCH`, `422 UNKNOWN_STATE`, `422 BUSINESS_IDENTITY_LOCKED`.

## 7. Customers

### 7.1 Shapes

Request (create and `PUT`):

```json
{
  "name": "Delhi Traders Pvt Ltd",
  "gstin": "07AABCD1234E1Z9",
  "stateCode": "07",
  "addressLine": "5 Karol Bagh",
  "city": "New Delhi",
  "pincode": "110005",
  "email": "accounts@delhitraders.example",
  "phone": "+911123456789"
}
```

Response adds read-only fields:

```json
{
  "id": 21, "name": "Delhi Traders Pvt Ltd", "gstin": "07AABCD1234E1Z9",
  "customerType": "B2B", "stateCode": "07", "stateName": "Delhi",
  "addressLine": "5 Karol Bagh", "city": "New Delhi", "pincode": "110005",
  "email": "accounts@delhitraders.example", "phone": "+911123456789",
  "active": true
}
```

### 7.2 Rules

- Required: `name` (up to 150 characters) and `stateCode`. Everything else is optional; the PDF omits what is missing.
- `gstin` is trimmed and uppercased before validation and uniqueness checks. If present: format and checksum validated, first two digits must match `stateCode`, unique per business (including inactive customers). Blank or omitted means B2C (`null`).
- `customerType` (`B2B` or `B2C`) is derived from the GSTIN and read-only.
- Optional `pincode` is 6 digits, optional `email` must be valid, optional `phone` is 7 to 15 characters of digits with an optional leading `+`.
- There is **no DELETE**. Customers are deactivated and reactivated. A used customer is never removed. Activate and deactivate are idempotent (`200` with the current record).
- Editing a customer affects drafts (their buyer details and tax split are recomputed on read, see 9.2) and never issued invoices (frozen snapshots).

### 7.3 List: `GET /api/customers`

Parameters: `search` (trimmed; empty means no search; case-insensitive contains on name, or GSTIN starts-with; max 100 characters), `active` (`true` or `false`; omitted means both), `page`, `size`. Fixed sort: `name ASC, id ASC`. Paginated envelope of customer objects.

### 7.4 Errors

- List: `400` only (bad query parameters or paging).
- Create and update: `400`, `409 DUPLICATE_CUSTOMER_GSTIN`, `422 INVALID_GSTIN`, `422 GSTIN_STATE_MISMATCH`, `422 UNKNOWN_STATE`.
- Detail, update, activate and deactivate: `404 NOT_FOUND` for an unknown or foreign id.

## 8. Items

### 8.1 Shapes

Request (create and `PUT`):

```json
{
  "name": "Logo design",
  "itemType": "SERVICE",
  "hsnSacCode": "998391",
  "unit": "NOS",
  "defaultPrice": "5000.00",
  "taxRateId": 4
}
```

Response:

```json
{
  "id": 8, "name": "Logo design", "itemType": "SERVICE",
  "hsnSacCode": "998391", "unit": "NOS", "defaultPrice": "5000.00",
  "taxRate": { "id": 4, "ratePercent": "18.00", "active": true },
  "active": true
}
```

### 8.2 Rules

- All six request fields are required. `itemType` is `GOODS` or `SERVICE`. `name` up to 150 characters.
- `hsnSacCode`: stored as a string (preserves leading zeros), **4 to 8 digits, provisional** until verified with a Chartered Accountant (real SAC codes are 6 digits).
- `unit`: free text, up to 20 characters. The frontend suggests common units but does not force them.
- `defaultPrice`: `0.00` or more, `NUMERIC(14,2)` range.
- Creating an item, or changing its rate, needs an **active** rate (`422 INVALID_TAX_RATE` for unknown or inactive). A `PUT` on an **inactive item** may keep its unchanged, now-inactive rate while other fields change; reactivating needs an active rate.
- No DELETE; activate and deactivate are idempotent. Item names are not unique.
- **Item edits never change existing draft lines.** Each invoice line carries its own description, HSN/SAC, unit, price and rate; items only pre-fill lines added afterwards.

### 8.3 List: `GET /api/items`

Parameters: `search` (case-insensitive contains on name, or HSN/SAC starts-with; max 100 characters), `active`, `page`, `size`. Fixed sort: `name ASC, id ASC`.

### 8.4 Errors

- List: `400` only (bad query parameters or paging).
- Create and update: `400`, `422 INVALID_TAX_RATE`.
- Detail, update, activate and deactivate: `404 NOT_FOUND` for an unknown or foreign id.

## 9. Invoices

### 9.1 Draft request

Create `POST /api/invoices` (`201`) takes `customerId`, `invoiceDate` and `lines`. Update `PUT /api/invoices/{id}` takes the same fields **plus `version`**, the value from the latest response. Create returns `version` 0.

```json
{
  "customerId": 21,
  "invoiceDate": "2026-10-04",
  "lines": [
    {
      "itemId": 8,
      "description": "Logo design",
      "hsnSacCode": "998391",
      "unit": "NOS",
      "quantity": "2.000",
      "unitPrice": "5000.00",
      "discountPercent": "10.00",
      "taxRatePercent": "18.00"
    }
  ]
}
```

Line rules:

- Required: `description` (up to 255 characters), `hsnSacCode` (4 to 8 digits), `unit` (up to 20), `quantity` (greater than 0, up to 3 decimals), `unitPrice` (0 or more), `taxRatePercent`. Optional: `itemId` (a reference only, `null` for a free-form line) and `discountPercent` (0 to 100, default `0.00`).
- The client sends every line field explicitly. The server copies nothing from the item, so a price override is always kept.
- `taxRatePercent` must equal an active rate (the line table stores the percent, and rates are unique by percent).
- A draft may have **zero lines**. Every line that is present must be fully valid at save.
- Maximum **100 lines**.
- `invoiceDate` is required. The future-date rule is checked at issue, not at save.
- Place of supply and tax type are never sent; they are derived (section 9.2).
- Customer: a new or changed customer must be active (`422 CUSTOMER_INACTIVE`). A draft whose customer was deactivated later can still be saved unchanged, but issue is rejected.
- Item: a line may use an inactive item only if the draft already references that item (`422 ITEM_INACTIVE` otherwise). There is no item check at issue.
- A `customerId` or `itemId` in the body that does not exist, or belongs to another business, returns the same `422 CUSTOMER_NOT_FOUND` or `422 ITEM_NOT_FOUND`, so existence is never revealed.
- Every computed amount, per line and in the totals, must fit `NUMERIC(14,2)`, otherwise `422 AMOUNT_OUT_OF_RANGE`.
- `PUT` writes no audit record. Create writes one `CREATED` record.

### 9.2 Invoice detail: `GET /api/invoices/{id}`

```json
{
  "id": 42, "status": "ISSUED",
  "invoiceNumber": "INV/2026-27/0007", "financialYear": "2026-27",
  "invoiceDate": "2026-10-04", "customerId": 21, "version": 2,
  "seller": { "name": "Ravi Designs", "gstin": "03ABCDE1234F1Z5", "stateCode": "03",
              "address": "12 Model Town, Ludhiana, 141002" },
  "buyer": { "customerId": 21, "name": "Delhi Traders Pvt Ltd", "gstin": "07AABCD1234E1Z9",
             "stateCode": "07", "address": "5 Karol Bagh, New Delhi, 110005" },
  "supplyType": "INTER_STATE", "placeOfSupplyStateCode": "07", "reverseCharge": false,
  "lines": [ {
    "lineNo": 1, "itemId": 8, "description": "Logo design",
    "hsnSacCode": "998391", "unit": "NOS",
    "quantity": "2.000", "unitPrice": "5000.00",
    "discountPercent": "10.00", "discountAmount": "1000.00",
    "taxableValue": "9000.00", "taxRatePercent": "18.00",
    "cgstAmount": "0.00", "sgstAmount": "0.00", "igstAmount": "1620.00",
    "lineTotal": "10620.00"
  } ],
  "totalTaxableValue": "9000.00", "totalCgst": "0.00", "totalSgst": "0.00",
  "totalIgst": "1620.00", "totalBeforeRoundOff": "10620.00",
  "roundOff": "0.00", "grandTotal": "10620.00",
  "amountInWords": "Rupees Ten Thousand Six Hundred Twenty Only",
  "createdBy": { "id": 7, "fullName": "Ravi Sharma" },
  "issuedAt": "2026-10-04T10:15:30Z", "paidAt": null,
  "cancelledAt": null, "cancelReason": null
}
```

Behaviour:

- **Issued, paid and cancelled invoices** return the frozen snapshot: `seller`, `buyer`, number, financial year, tax type and amounts exactly as issued. `invoiceNumber` and `financialYear` are `null` while DRAFT.
- **A draft** returns the **current** business and customer details, and its amounts are **recomputed on read** from current business and customer state (read-only, nothing is written), so a live buyer never appears next to a stale tax split. The draft PDF behaves the same way.
- The list shows a draft's **last-saved** totals. They differ from the detail only after a customer or business state change; re-saving fixes it. Issue always recomputes everything.
- `reverseCharge` is a server-owned constant `false` in v1 (no column behind it, never writable). It is an assumption flagged for CA review.
- `version` is the optimistic-locking counter. It increases on every change to the invoice row, including issue, pay and cancel (these change status and timestamps; the invoice's business content stays frozen). Only `PUT` on a draft uses it, so clients read it fresh from each response and never rely on a particular value on an issued invoice.
- Place of supply is the customer's state (a v1 simplification, also flagged). Tax type: seller state equals customer state gives `INTRA_STATE` (CGST + SGST, each half the rate), otherwise `INTER_STATE` (IGST).

### 9.3 Calculate (live preview)

The frontend holds no tax rules, so it asks the server. Both endpoints are Owner-only, save nothing, write no audit, run the same `TaxCalculator` as save and issue (so a preview always equals what a save would store), and take `{ "customerId": ..., "lines": [ ... ] }` with the same line objects as 9.1 (no date, no version). A response is a preview and does not claim the invoice is issueable.

- `POST /api/invoices/calculate`: for new drafts. An inactive customer or item is rejected outright.
- `POST /api/invoices/{id}/calculate`: for an existing draft, including unsaved edits. A customer or item already on that draft may stay even if inactive. A non-draft returns `422 INVOICE_NOT_DRAFT`.
- Empty `lines` returns zero totals, the tax type still derived from the customer, and `"Rupees Zero Only"`.

Example: seller and customer both in Punjab, one line of quantity `1.000`, price `999.99`, rate `18.00`:

```json
{
  "supplyType": "INTRA_STATE",
  "placeOfSupplyStateCode": "03",
  "lines": [ {
    "lineNo": 1, "itemId": 8, "description": "Logo design",
    "hsnSacCode": "998391", "unit": "NOS",
    "quantity": "1.000", "unitPrice": "999.99",
    "discountPercent": "0.00", "discountAmount": "0.00",
    "taxableValue": "999.99", "taxRatePercent": "18.00",
    "cgstAmount": "90.00", "sgstAmount": "90.00", "igstAmount": "0.00",
    "lineTotal": "1179.99"
  } ],
  "totalTaxableValue": "999.99",
  "totalCgst": "90.00", "totalSgst": "90.00", "totalIgst": "0.00",
  "totalBeforeRoundOff": "1179.99", "roundOff": "0.01", "grandTotal": "1180.00",
  "amountInWords": "Rupees One Thousand One Hundred Eighty Only"
}
```

The CGST is 999.99 x 18% / 2 = 89.9991, rounded to 90.00 (same for SGST). The exact total is 1179.99, so the grand total is 1180.00 and the round-off is +0.01.

### 9.4 Delete draft: `DELETE /api/invoices/{id}`

Returns `204`. Takes the same invoice row lock as issue, so a delete and an issue racing each other cannot both succeed. No version is needed. Writes one `DRAFT_DELETED` audit record. A non-draft returns `422 INVOICE_NOT_DRAFT`.

### 9.5 Lifecycle

Allowed transitions: DRAFT to ISSUED, ISSUED to PAID, ISSUED to CANCELLED. Everything else is rejected with `422 INVALID_STATUS_TRANSITION`. All three endpoints return `200` with the full detail of 9.2.

**Issue: `POST /api/invoices/{id}/issue`** (no body, no version needed)

- Follows design 7.3: lock the invoice row, validate, derive the financial year, recompute everything, take the next number and enforce date order in one conditional counter update, freeze the snapshot, write one audit record, commit.
- **Idempotent:** a repeat call on an already `ISSUED` invoice returns the identical stored response (same number and timestamps). It does not regenerate the number, recompute amounts, or write a second audit event. Because nothing is written, the `version` is unchanged too.
- A `PAID` or `CANCELLED` invoice returns `422 INVALID_STATUS_TRANSITION`.
- Re-validation at issue: at least one line (`INVOICE_HAS_NO_LINES`), every line valid, customer active, invoice date not in the future in Asia/Kolkata, date not earlier than the last issued date in that financial year, sequence not above 9999.
- `GSTIN_STATE_MISMATCH` at issue checks **both** sides: business GSTIN prefix against business state, and customer GSTIN prefix against customer state (only when the customer has a GSTIN). Normally impossible because registration, business update and customer edit already enforce it; it is a safety net, and the message names the failing side.
- The first issued invoice sets `identityLocked` on the business.

**Pay: `POST /api/invoices/{id}/pay`** (no body). Status must be `ISSUED`. Sets `PAID` and `paidAt`, writes one audit record.

**Cancel: `POST /api/invoices/{id}/cancel`**

```json
{ "reason": "Wrong customer selected" }
```

- `reason` is required, trimmed, 1 to 255 characters (`400` otherwise). It is stored in `cancel_reason` and returned as `cancelReason`.
- Status must be `ISSUED`. A `PAID` invoice cannot be cancelled in v1. A `DRAFT` also returns `INVALID_STATUS_TRANSITION`, with a message pointing to delete.
- Sets `CANCELLED` and `cancelledAt`; the number stays used; one audit record.

Pay and cancel take the invoice row lock first, then check the status.

### 9.6 List: `GET /api/invoices`

Parameters:

| Parameter | Meaning |
| --- | --- |
| `search` | Trimmed; empty means no search. Case-insensitive contains on invoice number or buyer name (the frozen name on issued invoices, the current customer name on drafts). Max 100 characters. Parameterized queries only. |
| `status` | `DRAFT`, `ISSUED`, `PAID`, `CANCELLED`. May repeat; values are ORed. Omitted means all. An unknown value returns `400`. |
| `dateFrom`, `dateTo` | Inclusive on `invoiceDate`. One bound alone is open-ended on the other side. `dateFrom` after `dateTo` returns `400`. |
| `customerId` | Must be numeric (`400` otherwise). A missing or foreign customer simply returns no results. |
| `page`, `size` | See 2.6. |

Fixed sort `invoiceDate DESC, id DESC`. Drafts appear unless filtered out. Row shape (no lines):

```json
{
  "id": 42, "status": "ISSUED", "invoiceNumber": "INV/2026-27/0007",
  "invoiceDate": "2026-10-04", "customerId": 21,
  "buyerName": "Delhi Traders Pvt Ltd", "buyerGstin": "07AABCD1234E1Z9",
  "supplyType": "INTER_STATE",
  "totalTaxableValue": "9000.00", "totalCgst": "0.00",
  "totalSgst": "0.00", "totalIgst": "1620.00",
  "roundOff": "0.00", "grandTotal": "10620.00"
}
```

### 9.7 PDF: `GET /api/invoices/{id}/pdf`

- `Content-Type: application/pdf`, `Content-Disposition: attachment`, `Cache-Control: no-store`.
- Filename: every `/` in the invoice number is replaced with `-`, for example `INV-2026-27-0007.pdf`. A draft is `draft-{id}.pdf`.
- Content per FR-4.7. The reverse-charge indicator always shows "No" in v1. Drafts carry a DRAFT watermark and use current data; cancelled invoices show their status and reason.
- Fetch with the token (section 2.9). A generation failure returns a generic `500`.

### 9.8 Audit history: `GET /api/invoices/{id}/audit`

Plain array, sorted `occurredAt ASC, id ASC`:

```json
[
  { "id": 101, "action": "CREATED", "occurredAt": "2026-10-04T09:00:00Z",
    "actor": { "id": 7, "fullName": "Ravi Sharma" }, "details": {} },
  { "id": 102, "action": "ISSUED", "occurredAt": "2026-10-04T10:15:30Z",
    "actor": { "id": 7, "fullName": "Ravi Sharma" },
    "details": { "invoiceNumber": "INV/2026-27/0007" } }
]
```

- `action` is exactly one of `CREATED`, `ISSUED`, `PAID`, `CANCELLED`, `DRAFT_DELETED`.
- `details` is `{}` for `CREATED`, `PAID` and `DRAFT_DELETED`, `{ "invoiceNumber": "..." }` for `ISSUED`, and `{ "reason": "..." }` for `CANCELLED`.
- `actor.fullName` is a **snapshot stored in the audit row at event time**, never resolved from the current user on read, so a rename never rewrites history. Email is never exposed.
- Audit is read-only through the API; only services write it. A deleted draft's rows stay in the database, but its id returns `404`, so they are not visible through the app in v1. A business-wide audit view is a release 2 candidate.

## 10. Reports and exports

All report queries are filtered by the JWT `business_id`; no endpoint accepts a `businessId` parameter.

### 10.1 Monthly summary: `GET /api/reports/monthly-summary?month=2026-10`

`month` is a **required** query parameter in `yyyy-MM` format; anything else, including `2026-13`, returns `400 VALIDATION_FAILED`. The summary counts only invoices with `status IN (ISSUED, PAID)` whose `invoiceDate` falls in that month. Drafts and cancelled invoices are excluded. A valid month with no matching invoices returns the zeroed response, not a `404`.

```json
{
  "month": "2026-10",
  "invoiceCount": 14,
  "totalTaxableValue": "125000.00",
  "totalCgst": "4500.00",
  "totalSgst": "4500.00",
  "totalIgst": "9000.00",
  "roundOff": "-0.40",
  "grandTotal": "143000.00"
}
```

Footing rule: taxable value, CGST, SGST and IGST equal the sums over the underlying invoices exactly, and grand total = taxable value + CGST + SGST + IGST + round-off.

### 10.2 CSV exports

Both exports take only `month` (same rules as 10.1), so the invoice CSV for a month always foots to that month's summary. Both include **Issued and Paid invoices only**.

**Format:** `Content-Type: text/csv; charset=utf-8`, `Content-Disposition: attachment; filename="..."`, UTF-8 with a byte-order mark (so Excel shows non-English names correctly), CRLF line endings, RFC 4180 quoting (fields with a comma, quote or line break are quoted and quotes doubled), a header row, dates as `yyyy-MM-dd`, money as plain decimals with 2 places (no thousands separators or currency symbol).

**Formula-injection protection applies to text cells only:** a text cell that starts with `=`, `+`, `-` or `@` gets a leading `'`. Numeric columns are never altered, because round-off can legitimately be negative (for example `-0.40`).

**Invoice CSV:** `GET /api/reports/invoices/export?month=2026-10`, filename `gstbridge-invoices-2026-10.csv`. Customer name and GSTIN come from the frozen buyer snapshot; a B2C GSTIN is blank. Sorted `invoiceDate ASC, id ASC` (which is also invoice-number order). An empty month gives a header-only file.

```
Number,Date,Customer,Customer GSTIN,Status,Taxable Value,CGST,SGST,IGST,Round Off,Grand Total
INV/2026-27/0007,2026-10-04,Delhi Traders Pvt Ltd,07AABCD1234E1Z9,ISSUED,9000.00,0.00,0.00,1620.00,0.00,10620.00
```

**Summary CSV:** `GET /api/reports/monthly-summary/export?month=2026-10`, filename `gstbridge-summary-2026-10.csv`. One row, produced by the same query as the on-screen summary. An empty month gives a zero row.

```
Month,Invoice Count,Taxable Value,CGST,SGST,IGST,Round Off,Grand Total
2026-10,14,125000.00,4500.00,4500.00,9000.00,-0.40,143000.00
```

A failure in the middle of a CSV stream cannot change the already-sent `200`, so the connection is aborted and the download fails instead of silently delivering a truncated file.

## 11. Operational endpoints

All public and all under `/api`.

| Path | Purpose |
| --- | --- |
| `GET /api/actuator/health` | Returns `{"status":"UP"}`. The only Actuator endpoint exposed. Used by the host as a health check in Stage 6. |
| `GET /api/docs` | OpenAPI spec (springdoc) |
| `GET /api/swagger-ui.html` | Swagger UI |

The docs expose the contract only, no data. In the deployed demo, the **try-it-out console is disabled by configuration** (the static schema stays public and portfolio-friendly); it stays enabled in local development. The demo holds synthetic data only.

## 12. Error code catalog

| Code | Status | When |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | Shape, precision, unknown or read-only fields, over 100 lines, bad month, filters or paging; `fieldErrors` filled |
| `MALFORMED_REQUEST` | 400 | Body is not parseable JSON |
| `UNAUTHENTICATED` | 401 | Missing, invalid or expired token |
| `INVALID_CREDENTIALS` | 401 | Wrong email or password, or inactive user |
| `ACCESS_DENIED` | 403 | Role not allowed (including an inactive membership on a protected call) |
| `NOT_FOUND` | 404 | Unknown id, or a record of another business |
| `DUPLICATE_EMAIL` | 409 | Email already registered |
| `DUPLICATE_GSTIN` | 409 | GSTIN already used by another business |
| `DUPLICATE_CUSTOMER_GSTIN` | 409 | GSTIN already used by another customer of this business |
| `INVITATION_ALREADY_PENDING` | 409 | Pending invitation exists for that email and business |
| `ALREADY_MEMBER` | 409 | Invitee email is already an active member |
| `STALE_VERSION` | 409 | Draft `PUT` with an old `version` |
| `INVALID_GSTIN` | 422 | GSTIN checksum fails |
| `GSTIN_STATE_MISMATCH` | 422 | GSTIN prefix differs from the state (business or customer; message names which) |
| `UNKNOWN_STATE` | 422 | `stateCode` not in the state table |
| `BUSINESS_IDENTITY_LOCKED` | 422 | GSTIN or prefix changed after the first issued invoice |
| `INVITATION_INVALID` | 422 | Token unknown, expired, revoked, used, or email mismatch |
| `INVITATION_NOT_PENDING` | 422 | Revoking an invitation that is not pending |
| `CANNOT_DEACTIVATE_OWNER` | 422 | Deactivating an Owner membership |
| `INVALID_TAX_RATE` | 422 | Rate unknown or inactive where an active one is required |
| `CUSTOMER_NOT_FOUND` / `ITEM_NOT_FOUND` | 422 | Referenced id in a body is missing or belongs to another business |
| `CUSTOMER_INACTIVE` / `ITEM_INACTIVE` | 422 | Inactive selection not allowed |
| `AMOUNT_OUT_OF_RANGE` | 422 | A computed amount does not fit `NUMERIC(14,2)` |
| `INVOICE_NOT_DRAFT` | 422 | Edit, delete or calculate on a non-draft |
| `INVALID_STATUS_TRANSITION` | 422 | Transition outside the allowed set (retry of issue on ISSUED is not an error) |
| `INVOICE_HAS_NO_LINES` | 422 | Issuing a draft with no lines |
| `INVOICE_DATE_IN_FUTURE` | 422 | Invoice date after today in Asia/Kolkata |
| `INVOICE_DATE_OUT_OF_ORDER` | 422 | Date earlier than the last issued date in that financial year |
| `SEQUENCE_EXHAUSTED` | 422 | The 10,000th issue in a financial year |
| `INTERNAL_ERROR` | 500 | Unexpected error; generic message, no stack trace |

## 13. Assumptions and v1 limitations

1. Place of supply is the customer's state; reverse charge is always "No". Both are simplifications flagged for CA review.
2. Draft lists show last-saved totals; draft detail and draft PDF recompute on read.
3. An existing user with several memberships who accepts an invitation lands in the earliest active membership. No business switcher in v1.
4. Customers, items and the business profile are last-write-wins.
5. Tax rates are changed by migration only, never through the API.
6. Exports are by calendar month only; date ranges and financial-year exports are release 2 candidates.
7. Audit is per invoice only; deleted drafts' audit rows are not exposed.
8. No brute-force login protection, password reset or refresh tokens (deferred in the design document).
9. Downloads need a fetch with the token; plain links do not work.

## 14. Design-document fixes arising from this stage

To be applied in System Design v1.3 (and PRD v1.3 where noted):

1. **7.5 Mark paid and cancel:** both take the invoice row lock (`SELECT ... FOR UPDATE`) first, then check status, like issue (7.3). Without it, concurrent pay and cancel could both pass the status check.
2. **7.3 step 3:** also check that the business GSTIN prefix matches the business state, not only the customer; raise `GSTIN_STATE_MISMATCH`.
3. **Section 8 (CSV):** formula-injection escaping applies to text cells only, with a leading `'`; numeric columns are never altered (negative round-off). Add the format details: UTF-8 BOM, CRLF, RFC 4180 quoting.
4. **6.3 `audit_log`:** add an `actor_name` column holding the actor's full name at event time, and keep `actor_user_id`.
5. **7.2 point 8 and section 11:** draft detail and draft PDF recompute from current business and customer state on read; the list shows last-saved totals.
6. **Section 16 assumptions:** add place of supply = customer's state and reverse charge constant `false`; add both to open item 4 (CA review).
7. **Sections 6.3, 7.4 and 9:** `invoice_prefix` is `A-Z0-9` only; password is 8 to 72 UTF-8 bytes; invitation expiry is 7 days and the link uses a configured frontend origin; strict JSON parsing (`FAIL_ON_UNKNOWN_PROPERTIES`) on every endpoint.
8. **Section 4.1 `auth` module:** add member management (list members, deactivate an Accountant) and the invitation list and revoke endpoints.
9. **Section 10:** operational endpoints (`/api/actuator/health`, `/api/docs`, `/api/swagger-ui.html`) sit under `/api`; paging errors return `400` instead of clamping; reference this document for the error catalog.
10. **Section 13 test plan:** add the tests in section 15 below.
11. **PRD v1.3 (optional):** FR-1.3 or an NFR note that members can be listed and an Accountant deactivated, if you want the requirement stated, not only designed.

## 15. Test additions

- **Calculate parity:** for random invoices, `POST /api/invoices/calculate` equals what `PUT` stores and what issue freezes.
- **Idempotent issue:** a repeat issue returns an identical body, consumes no number, and writes one audit row only.
- **Pay and cancel race:** simultaneous pay and cancel on one invoice leave exactly one winner and one `422`.
- **CSV and summary footing:** the invoice CSV for a month sums to the monthly summary; a negative round-off survives unescaped; a customer name starting with `=` is escaped; an empty month returns the documented outputs.
- **Audit:** exact action values and `details` shapes; the actor name is unchanged after the user is renamed.
- **Strict parsing:** unknown and read-only fields return `400` on every write endpoint.
- **Limits:** 101 lines, an amount out of range, quantity precision, and a password over 72 bytes are all rejected.
- **Invitation token:** consumed once only, hashed at rest, wrong-email and expired tokens give the same generic error.
- **Draft recompute:** changing a customer's state changes a draft's detail and PDF but never an issued invoice.
- **Business lock:** changing the GSTIN or prefix after the first issue returns `422`, including when racing the first issue.
- **Tenant scoping:** no endpoint accepts `businessId`; foreign ids in bodies return the same `422` as missing ones.

## 16. Open items

1. Verify with official GST sources or a Chartered Accountant: seeded tax rates, the s.170 rounding behaviour, the e-invoicing threshold, the place-of-supply simplification, the reverse-charge assumption, and the HSN/SAC length rule (4 to 8 digits is provisional).
2. Confirm the details added while compiling this document: the shapes of `/me`, the invitation list and the member list; the codes `MALFORMED_REQUEST`, `UNAUTHENTICATED`, `ACCESS_DENIED`, `INTERNAL_ERROR`, `ALREADY_MEMBER`, `INVITATION_NOT_PENDING` and `CANNOT_DEACTIVATE_OWNER`; field limits (line description up to 255 characters, phone format); the `fieldErrors` entry shape; invitation `EXPIRED` computed on read; a wrong password on accept returning `401`.
3. Swagger try-it-out in the deployed demo: disabled by default here; flip it if you prefer reviewers to try the API live.
4. Apply the design-document fixes in section 14 (System Design v1.3).

## 17. Changes

Version 1.0: first version, compiled from six review rounds (conventions, auth and business, customers and items, invoice drafts and calculation, lifecycle, list and PDF, reports and audit).
