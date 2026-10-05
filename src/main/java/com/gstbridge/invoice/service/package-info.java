/**
 * Invoice business-logic services ({@code @Service}).
 *
 * <p>Services depend only on the corresponding {@code repository} package; they
 * never depend on controllers.
 *
 * <h2>Pure-logic types (no database access) hosted here</h2>
 * <ul>
 *   <li>{@code TaxCalculator}
 *   <li>{@code AmountInWords}
 *   <li>{@code InvoiceNumberFormatter}
 *   <li>{@code FinancialYear}
 * </ul>
 */
package com.gstbridge.invoice.service;
