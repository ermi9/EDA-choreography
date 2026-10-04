package com.eda.choreography.domain.step;

import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.Map;

/**
 * A service's own work for one step. It sees everything accumulated so far and returns its
 * result as a JSON object: strings, numbers, booleans, null, lists and nested maps.
 */
@FunctionalInterface
public interface StepAction {

    Map<String, ?> execute(ChoreographyMessage message);
}
