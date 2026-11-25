package com.eda.choreography.infra.participant;

import com.eda.choreography.domain.resolution.ServiceTable;
import com.eda.choreography.infra.host.ServiceConsumers;
import com.eda.choreography.infra.host.ServiceHost;
import com.eda.choreography.infra.web.ServiceSpecs;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Off by default: one engine of a deployment, or several, is told to take up participants. */
@Configuration(proxyBeanMethods = false)
public class ParticipantConfig {

    @Bean
    Participants participants(
            @Value("${choreography.participants.enabled}") boolean enabled,
            @Value("${choreography.participants.call-timeout}") Duration callTimeout,
            @Value("${choreography.participants.look-every}") Duration lookEvery,
            ServiceTable table,
            ServiceSpecs specs,
            ServiceConsumers consumers,
            ServiceHost host) {
        return new Participants(enabled, table, specs, consumers, host.services(), callTimeout, lookEvery);
    }
}
