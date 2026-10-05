# GSTBridge: Product Requirements Document

**Version:** 1.2 | **Author:** Amandeep Kumar | **Date:** 4 Oct 2026 | **Status:** Revised after design review, approved for design

> **Changes from 1.1 and 1.0:** listed in section 15. Items for release 2 are listed in section 12.

## 1. Overview

GSTBridge is a multi-tenant web application that helps small Indian businesses and freelancers create GST-format tax invoices with a correct tax split and see how much GST they collected each month. It is built for regular taxpayers below the e-invoicing turnover threshold. Filing returns stays with the user's Chartered Accountant.

**Name meaning:** GSTBridge bridges the gap between a business's day-to-day invoicing and tax compliance. It produces invoices with a correct tax split and reports an accountant can use directly.

**Build note:** This PRD is shared by all three build versions (v1 no AI, v2 moderate AI, v3 high AI). Scope is identical. Only the build process differs. The primary build is v2.

## 2. Problem Statement

Small businesses make frequent GST invoicing mistakes: wrong tax split (CGST/SGST vs IGST), missing mandatory fields, invalid GSTINs, duplicate or out-of-sequence invoice numbers, and arithmetic or rounding errors. They also struggle to know how much tax they have collected in a month. Spreadsheets and hand-made invoices make this error-prone.

## 3. Goals and Non-Goals

**Goals**

- G1: Produce GST-format invoices with correct, automatic tax calculation for regular taxpayers.
- G2: Prevent common errors through validation and immutable issued invoices.
- G3: Give a clear monthly tax summary with CSV export.
- G4: Keep each business's data fully private (multi-tenant isolation).
- G5: Demonstrate industry-style engineering: documented, tested, deployed.

**Non-Goals (v1)** E-invoicing (IRN), e-way bills, direct GST portal filing, payment gateways, inventory management, mobile app, multi-currency, GSTR-1 export (later), credit notes (release 2).

**Product limit:** businesses above the e-invoicing turnover threshold (reported as ₹5 crore aggregate turnover; verify against the current notification) must generate an IRN, which this product does not do. An invoice from GSTBridge is not sufficient for them.

## 4. Users and Roles

| Role | Description | Permissions |
| --- | --- | --- |
| **Owner** | Small business owner or freelancer who registers the business | Everything: manage profile, customers, products, invoices (create, issue, cancel, mark paid), reports, and Accountant access |
| **Accountant** | Invited by the Owner (CA or bookkeeper); the demo has a seeded Accountant | View invoices, customers, products, and reports. Export CSV. Cannot create, edit, cancel, or delete anything |

**Primary persona:** Ravi, a freelance designer in Punjab who sells to clients in Punjab and Delhi. He is not sure when to charge IGST versus CGST+SGST and wants invoices he can trust.

## 5. Scope and Priorities (MoSCoW)

| Priority | Feature |
| --- | --- |
| **Must** | Register and log in; business profile; customer management with GSTIN validation; product/service catalog (HSN/SAC, rate); invoice creation with automatic tax split; invoice lifecycle; PDF invoice; invoice list with search and filter; monthly tax summary; role-based access; audit trail |
| **Should** | CSV export of invoices and summary; invite Accountant by copyable link |
| **Could** | GSTR-1-style export; recurring invoices; email invoice to customer; dashboard charts |
| **Won't (v1)** | Items listed under Non-Goals |

## 6. Functional Requirements

Each requirement has an ID. Acceptance criteria (AC) define "done".

### FR-1 Authentication and Business Setup

- **FR-1.1** As an Owner, I can register with email and password and create my business.
  - AC: Password is stored hashed (BCrypt). Duplicate email is rejected. Registration creates a business and an Owner user.
- **FR-1.2** As an Owner, I can set up a business profile: legal name, GSTIN, address, state, invoice prefix.
  - AC: GSTIN passes format and checksum validation. State is derived from the GSTIN's first two digits and must match the selected state.
  - AC: GSTIN and invoice prefix (maximum 3 characters) cannot be changed once the business has any issued invoice.
- **FR-1.3** As a user, I can log in and stay logged in using a token.
  - AC: Unauthenticated requests to protected endpoints return 401. Wrong role returns 403.
  - AC: Role and active status are read from the user's current membership on every request, so revoking access or changing a role takes effect immediately.

### FR-2 Customers

- **FR-2.1** As an Owner, I can create, edit, search, and deactivate customers.
  - AC: A customer with a GSTIN is validated (format and checksum) and treated as registered (B2B). A customer without a GSTIN is unregistered (B2C), and a state is then required manually.
  - AC: For a customer with a GSTIN, the first two digits must match the selected state; a mismatch is rejected.
  - AC: A customer used on an issued invoice cannot be hard-deleted, only deactivated.

### FR-3 Product/Service Catalog

- **FR-3.1** As an Owner, I can manage items with name, HSN/SAC code, unit, default price, and GST rate.
  - AC: GST rate must exist and be active in the configurable tax-rate list, which is seeded from the current official rate schedule and checked before release. Price must be greater than or equal to 0. Changing an item later does not change already issued invoices.

### FR-4 Invoices

- **FR-4.1** As an Owner, I can create a draft invoice by choosing a customer, date, and line items (quantity, price, rate, optional discount).
  - AC: Per line: quantity greater than 0, unit price greater than or equal to 0, discount between 0 and 100 percent, and the rate must be an active rate. Invalid lines are rejected.
- **FR-4.2** The system decides the tax type automatically.
  - AC: Seller state equals buyer state: CGST + SGST (each half of the rate). Different state: IGST (full rate). Unit tests cover both cases and every seeded rate.
- **FR-4.3** The system calculates line amounts, tax, totals, and round-off.
  - AC: All money uses exact decimal arithmetic (BigDecimal / NUMERIC). Tax is calculated per line and rounded to 2 decimals (HALF_UP). Grand total is rounded to the nearest rupee and the round-off is shown separately.
  - AC: Rounding is documented as two decisions: nearest-rupee rounding of the grand total follows the GST Act rounding provision (s.170, verify before release); rounding each line component to 2 decimals is a software convention.
- **FR-4.4** As an Owner, I can issue a draft.
  - AC: On issue, the invoice gets a sequential number unique per business per financial year (for example `INV/2026-27/0001`) with no gaps or duplicates, even under concurrent requests. Financial year runs 1 April to 31 March.
  - AC: The invoice date is validated in Indian Standard Time: it cannot be in the future and cannot be earlier than the last issued invoice date in that financial year.
  - AC: Issuing the same draft twice (including simultaneously) returns the same invoice and consumes exactly one number.
- **FR-4.5** Issued invoices are immutable.
  - AC: Any attempt to edit an issued invoice is rejected. In v1, a correction is made by cancelling the invoice and issuing a new one dated today. Cancelling is an internal status, not a legal substitute for a credit note. Credit notes arrive in release 2.
- **FR-4.6** As an Owner, I can mark an issued invoice as Paid, or cancel it with a reason.
  - AC: Allowed transitions are Draft to Issued, Issued to Paid, Issued to Cancelled. All other transitions are rejected, so a Paid invoice cannot be cancelled in v1. Cancelled invoices keep their number and are excluded from tax totals.
- **FR-4.7** As a user, I can download an invoice as PDF.
  - AC: PDF shows seller and buyer details and GSTINs, invoice number and date, place of supply, per-line description, quantity, unit, rate, HSN/SAC, taxable value, tax breakup, reverse-charge indicator, totals, amount in words, and a signature block.
  - AC: Drafts carry a DRAFT watermark. Cancelled invoices show their status and reason. A draft has no frozen data yet, so its PDF is built from the current business, customer and line data.
- **FR-4.8** As a user, I can list, search, and filter invoices by number, customer, status, and date range, with pagination.
  - AC: Default page size is 20, maximum 100, default order newest invoice date first.

### FR-5 Reports

- **FR-5.1** As an Owner or Accountant, I can see a monthly summary: invoice count, total taxable value, total CGST, SGST, IGST, round-off, and grand total.
  - AC: Counts Issued and Paid invoices only; drafts and cancelled invoices are excluded.
  - AC: Taxable value, CGST, SGST and IGST totals equal the sums over the underlying invoices exactly. Round-off is its own total, and grand total = taxable value + taxes + round-off.
- **FR-5.2** I can export invoices and the summary as CSV. (Should)
  - AC: Invoice CSV columns: number, date, customer, customer GSTIN, status, taxable value, CGST, SGST, IGST, round-off, grand total.
  - AC: Summary CSV is one row for the chosen month with columns: month (YYYY-MM), invoice count, taxable value, CGST, SGST, IGST, round-off, grand total. The figures equal the on-screen summary.
  - AC: Both exports include Issued and Paid invoices only.

### FR-6 Audit Trail

- **FR-6.1** The system records who created, issued, paid, or cancelled each invoice, and when.
  - AC: Create, issue, pay, cancel and draft-delete each write exactly one audit record.
  - AC: Audit records cannot be edited or deleted through the app or by the application's database login.

## 7. Business Rules (Reference)

| # | Rule |
| --- | --- |
| BR-1 | Intra-state sale gives CGST + SGST. Inter-state sale gives IGST. |
| BR-2 | GSTIN is 15 characters. The first 2 digits are the state code. The last character is a checksum and must validate. |
| BR-3 | Invoice numbers are sequential per business per financial year, max 16 characters, with no gaps. |
| BR-4 | Issued invoices cannot be edited. In v1, cancel and re-issue; credit notes come in release 2. |
| BR-5 | A customer without GSTIN is B2C (unregistered). |
| BR-6 | Cancelled and draft invoices never count in tax totals. |
| BR-7 | Grand total is rounded to the nearest rupee (50 paise and above rounds up). Line components are rounded to 2 decimals, HALF_UP. |
| BR-8 | Invoice lines need quantity greater than 0, price of 0 or more, discount from 0 to 100 percent, and an active tax rate. |
| BR-9 | Invoice dates use Indian Standard Time and cannot be in the future or earlier than the last issued date in that financial year. |

*Note: these rules are a simplified model for a portfolio product, not tax advice. Real filings should always be verified with a Chartered Accountant.*

## 8. Non-Functional Requirements

| Area | Requirement |
| --- | --- |
| **Security** | BCrypt password hashing. JWT authentication. HTTPS in deployment. Role checks on every endpoint. Strict tenant isolation: every query is scoped to the user's business, one business can never read another's data, and the database rejects cross-business references. Separate database logins for migrations and for the running app. |
| **Speed** | Typical API calls respond in under 300 ms (95th percentile). Invoice list loads in under 2 seconds with 10,000 invoices (indexed and paginated). PDF generation completes in under 3 seconds. Monthly summary returns in under 2 seconds for 10,000 invoices. |
| **Correctness** | Tax and invoice logic is covered by the enumerated test list in the design doc (section 13): every rate for intra- and inter-state, round-off edge cases, discount limits, GSTIN checks, number-length limit, concurrency, tenant isolation and an authorization matrix. Exact decimal arithmetic for all money. |
| **Reliability** | Input validation on all endpoints. Clear, consistent error messages. Invoice issue is transactional (all or nothing). |
| **Auditability** | Audit trail per FR-6. |
| **Maintainability** | Layered code structure, API documentation (OpenAPI/Swagger), README with setup steps. |
| **Usability** | Create and issue an invoice in under 2 minutes for a returning user. Works on desktop and mobile browsers. |

## 9. Key User Flows

1. **Onboarding:** Register, create business and GSTIN, add first customer and item, create first invoice.
2. **Create invoice:** Pick customer, add lines, system shows tax split live, save draft, review, issue, download PDF.
3. **Month end:** Open summary, pick month, review totals, export CSV for the accountant.
4. **Correction:** Open issued invoice, cancel with reason, create a new corrected invoice dated today. (Credit notes: release 2.)

## 10. Success Metrics

- All Must-have requirements pass their acceptance criteria.
- All enumerated tests in the design doc pass, including the concurrency, tenant-isolation and authorization-matrix tests.
- NFR speed targets met in a test with 10,000 sample invoices.
- App deployed with a public demo link and synthetic sample data.
- Complete documentation set: PRD, design doc, API docs, README, test report.
- Build log comparing effort and quality across v1, v2, v3 (as time permits).

## 11. Assumptions and Risks

| Item | Detail | Mitigation |
| --- | --- | --- |
| Learning GST from zero | Builder has no GST background | Glossary below, validate rules with a CA or official GST sources before finalizing |
| Scope creep | Biggest risk for a solo project | MoSCoW list is the contract; new ideas go to a "later" list |
| Tax rules change | Real GST rules and rates change | Keep rates configurable, not hard-coded |
| Tax rule accuracy | Rates, rounding and the e-invoicing threshold come from AI-assisted research | Verify against official sources or a CA before release |
| Concurrency on invoice numbers | Duplicate numbers under simultaneous requests | Database-level uniqueness and locking, with a test |
| Time | Solo builder, wants to finish fast | Strict MVP, deploy early, polish last |

**Assumptions:** single currency (INR), single GSTIN per business, English UI, standard regular-taxpayer rules only, invoice dates in Indian Standard Time.

## 12. Roadmap

| Stage | Output |
| --- | --- |
| 1 | PRD (this document) |
| 2 | System design: architecture, database schema (ER diagram), module breakdown |
| 3 | API design: endpoints, request and response models, error format |
| 4 | Implementation plan: task breakdown, milestones |
| 5 | Build and test: code, unit and integration tests |
| 6 | Deployment and demo data |
| 7 | Final documentation, README, build log |

**Timeline estimate:** at 5 to 6 hours a day, the MVP including docs is roughly 3 to 4 weeks. This is an estimate, and stages 2 and 3 will refine it.

**Release 2 (after competitor research):** credit notes, PDF refinements beyond FR-4.7, further CSV and pagination options, and whatever the research shows is worth building.

## 13. Glossary

| Term | Meaning |
| --- | --- |
| **GST** | Goods and Services Tax, India's indirect tax on sales |
| **GSTIN** | 15-character GST registration number of a business |
| **CGST / SGST** | Central and State GST, charged together on same-state sales (each half the rate) |
| **IGST** | Integrated GST, charged on sales between two states |
| **HSN / SAC** | Classification codes for goods (HSN) and services (SAC) |
| **B2B / B2C** | Sale to a registered business (has GSTIN) or to a consumer (no GSTIN) |
| **Taxable value** | Price of the item before tax |
| **Credit note** | Document that reduces or reverses a previously issued invoice |
| **Financial year** | 1 April to 31 March |
| **IRN / e-invoice / e-way bill** | Government-portal documents for larger businesses and goods movement (out of scope) |
| **Tenant** | One business and its private data inside the shared system |

## 14. Open Questions

1. Resolved in 1.2: the frontend is React (design doc decision 2). Thymeleaf is used only for the PDF template.
2. Hosting platform for the demo (decide in stage 6).
3. Resolved in 1.1: credit notes move to release 2.

## 15. Changes

### From 1.1 to 1.2

- FR-4.7: a draft's PDF is built from current data, because a draft has no frozen snapshot.
- FR-5.2: summary CSV columns defined (one row per month); the two exports are now specified separately.
- Open question 1 (frontend choice) marked resolved: React.

### From 1.0 to 1.1

- Product claim changed from "GST-compliant" to "GST-format invoices with a correct tax split for regular taxpayers"; product limit (e-invoicing threshold) stated.
- GST rate list made configurable (active rates in `tax_rate`) instead of a fixed five-value list.
- Added line validation rules, IST date rule, same-draft idempotency, and rounding decisions (BR-7 to BR-9).
- Correction policy made explicit: cancel and re-issue in v1; Paid invoices cannot be cancelled; credit notes in release 2.
- Reports: round-off shown as its own total; footing rule restated; CSV columns defined.
- PDF: place of supply, per-line details, reverse-charge indicator, signature block, DRAFT and cancelled markings.
- Business GSTIN and prefix locked after the first issued invoice; customer GSTIN must match its state.
- Role and status read from the membership row on every request.
- Audit AC made testable; audit rows protected from the application's database login.
- Pagination limits added.
- Coverage percentage replaced by an enumerated test list.
