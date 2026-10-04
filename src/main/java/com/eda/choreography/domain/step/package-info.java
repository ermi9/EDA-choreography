/**
 * One service's step loop and the ports it needs: the step's own work, the next-hop decision,
 * and a publisher. Adapters implement the ports; this package never sees a broker (INC-3).
 */
package com.eda.choreography.domain.step;
