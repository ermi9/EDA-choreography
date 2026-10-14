/**
 * The HTTP side (INC-7): the authoring API the editor talks to (catalog, flows, runs), and the
 * health endpoint discovery probes for each hosted service. Nothing here takes part in running an
 * instance; that happens over Kafka.
 */
package com.eda.choreography.infra.web;
