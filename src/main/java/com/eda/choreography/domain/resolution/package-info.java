/**
 * Turning a logical service name into where to send it work: a local table built from the
 * discovery platform's {@code service-events} stream (INC-5).
 *
 * <p>The table is this engine's own reading of discovery's published contract. It depends on
 * the event's JSON fields, never on a discovery class.
 */
package com.eda.choreography.domain.resolution;
