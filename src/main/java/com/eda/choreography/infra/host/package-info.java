/**
 * Hosting services (INC-7): one engine process runs the steps of the services it is configured
 * with, each on its own topic and consumer group, exactly as separate services would. The step
 * work is a labeled demo; real services bring their own {@code StepAction}.
 */
package com.eda.choreography.infra.host;
