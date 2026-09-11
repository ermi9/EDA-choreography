/**
 * The pure core: trace model, compensation ordering, and the join state machine.
 *
 * <p>Nothing in this package tree may import Kafka, Redis, or Spring. It is exercised by
 * plain unit tests and reached from the outside only through the {@code infra} adapters.
 * {@code ArchitectureTest} fails the build if this boundary is crossed.
 */
package com.eda.choreography.domain;
