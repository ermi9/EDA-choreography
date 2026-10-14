/**
 * The plan a flow's instances run (INC-5): its steps, the edges between them with their
 * conditions, and where it forks and joins. A plan is produced once, by a {@code Planner}, and
 * only read at runtime. Each step reads it to choose its own next hop; nothing executes it
 * centrally.
 */
package com.eda.choreography.domain.plan;
