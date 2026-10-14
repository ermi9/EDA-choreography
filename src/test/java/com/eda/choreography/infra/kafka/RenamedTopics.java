package com.eda.choreography.infra.kafka;

import com.eda.choreography.domain.resolution.Resolution;
import com.eda.choreography.domain.resolution.ResolutionSource;
import java.util.Optional;

/**
 * Test fixture: a discovery table that gives every service topics its name alone would not
 * suggest ({@code <name>.work} and {@code <name>.undo}), all live. A publisher that sends there
 * took the topic from the table.
 */
final class RenamedTopics {

    private RenamedTopics() {
    }

    static ResolutionSource everyServiceLive() {
        return name -> Optional.of(new Resolution(name + ".work", name + ".undo", true));
    }
}
