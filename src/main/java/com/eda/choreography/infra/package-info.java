/**
 * Adapters that give the domain a body: Kafka consumers/producers (INC-3), the Redis-backed
 * join state store (INC-4), the consumer of discovery's change stream and the SpEL condition
 * language (INC-5). Infra calls into the domain, never the reverse.
 */
package com.eda.choreography.infra;
