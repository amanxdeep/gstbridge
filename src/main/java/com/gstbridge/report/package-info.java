/**
 * Report feature module of GSTBridge.
 *
 * <h2>Module architecture rules</h2>
 * <ul>
 *   <li><b>Layering:</b> A layer only calls the layer directly below it:
 *       {@code controller -> service -> repository}.</li>
 *   <li><b>Cross-module calls:</b> A module may call another module only
 *       through that module's service, never its repository or entities.</li>
 * </ul>
 *
 * <p>Sub-packages: {@code controller}, {@code dto}, {@code service},
 *     {@code repository}, {@code entity}, {@code mapper}.
 */
package com.gstbridge.report;
