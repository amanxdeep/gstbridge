/**
 * Shared, cross-module utilities that never touch the database.
 *
 * <h2>Pure-logic types (no database access) hosted here</h2>
 * <ul>
 *   <li>{@code GstinValidator} &mdash; stateless GSTIN validation logic.
 * </ul>
 *
 * <p>Cross-cutting: this package has no sub-packages.
 *
 * <p>General module rules also apply here: a layer only calls the layer
 * directly below it (controller -&gt; service -&gt; repository), and a module
 * may call another module only through that module's service.
 */
package com.gstbridge.common;
