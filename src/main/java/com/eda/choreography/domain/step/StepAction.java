package com.eda.choreography.domain.step;

import com.eda.choreography.domain.message.ChoreographyMessage;

/** A service's own work for one step. It sees everything accumulated so far and returns its result. */
@FunctionalInterface
public interface StepAction {

    String execute(ChoreographyMessage message);
}
