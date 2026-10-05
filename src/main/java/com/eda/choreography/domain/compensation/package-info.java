/**
 * Reverse-topological compensation over a trace (INC-1), and the walk that carries it out at
 * runtime: a trigger that starts the first stage and a runner per service that undoes its own
 * entries stage by stage (INC-4).
 */
package com.eda.choreography.domain.compensation;
