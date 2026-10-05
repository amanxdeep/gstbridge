/**
 * Audit persistence: Spring Data repositories.
 *
 * <h2>Tenant table rule</h2>
 * Every repository method that reads or writes a <em>tenant</em> table must
 * accept {@code businessId} as a parameter (an ArchUnit test enforces this).
 */
package com.gstbridge.audit.repository;
