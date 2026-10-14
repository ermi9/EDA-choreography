/**
 * The consumer of the discovery platform's {@code service-events} topic, which keeps this
 * engine's {@code ServiceTable} current (INC-5). Discovery is a black box: only the published
 * JSON schema is used, read into the engine's own {@code ServiceEvent}.
 */
package com.eda.choreography.infra.discovery;
