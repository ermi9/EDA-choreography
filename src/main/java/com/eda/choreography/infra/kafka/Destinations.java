package com.eda.choreography.infra.kafka;

import com.eda.choreography.domain.resolution.Resolution;
import com.eda.choreography.domain.resolution.ResolutionSource;
import com.eda.choreography.domain.resolution.UnresolvedServiceException;
import java.util.Objects;

/**
 * Where to send a step's work, its undo requests and a join's timeout notice, looked up in
 * discovery's table rather than derived from the name.
 *
 * <p>New work goes only to a live service, as discovery's contract asks. Undoing a step and
 * timing out a join only need the service to be known: the request waits on its topic until the
 * service is back, and a service that was live when it ran a step stays known after it left.
 * Throwing here leaves the record to the caller's retries.
 */
final class Destinations {

    private final ResolutionSource resolutions;

    Destinations(ResolutionSource resolutions) {
        this.resolutions = Objects.requireNonNull(resolutions, "resolutions");
    }

    String workFor(String serviceName) {
        var resolution = known(serviceName);
        if (!resolution.live()) {
            throw new UnresolvedServiceException(serviceName + " is not live, so it is sent no work");
        }
        return resolution.inputTopic();
    }

    String undoFor(String serviceName) {
        return known(serviceName).compensationTopic();
    }

    String timeoutNoticeFor(String joinName) {
        return known(joinName).inputTopic();
    }

    private Resolution known(String serviceName) {
        return resolutions.resolve(serviceName).orElseThrow(() ->
                new UnresolvedServiceException("discovery does not know " + serviceName));
    }
}
