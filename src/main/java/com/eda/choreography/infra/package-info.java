/**
 * Adapters that give the domain a body: Kafka consumers/producers (INC-3) and the
 * Redis-backed join state store (INC-4). Infra calls into the domain, never the reverse.
 */
package com.eda.choreography.infra;
