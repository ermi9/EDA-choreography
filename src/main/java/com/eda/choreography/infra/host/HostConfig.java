package com.eda.choreography.infra.host;

import com.eda.choreography.domain.compensation.CompensationPublisher;
import com.eda.choreography.domain.compensation.CompensationTrigger;
import com.eda.choreography.domain.join.JoinDeadlines;
import com.eda.choreography.domain.join.JoinStateMachine;
import com.eda.choreography.domain.plan.Conditions;
import com.eda.choreography.domain.resolution.ServiceTable;
import com.eda.choreography.domain.step.MessagePublisher;
import com.eda.choreography.infra.flows.StoredPlans;
import com.eda.choreography.infra.kafka.StepContainerFactory;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The services this process hosts: {@code choreography.host.services}, a comma-separated list,
 * empty by default (an engine that only serves the editor).
 */
@Configuration(proxyBeanMethods = false)
public class HostConfig {

    @Bean
    DiscoveryRegistration discoveryRegistration(
            @Value("${choreography.host.discovery-url:}") String discoveryUrl,
            @Value("${choreography.host.public-url:}") String publicUrl) {
        return new DiscoveryRegistration(discoveryUrl, publicUrl);
    }

    @Bean
    ServiceHost serviceHost(
            @Value("${choreography.host.services:}") List<String> services,
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${choreography.host.partitions:3}") int partitions,
            @Value("${choreography.join.default-timeout}") Duration defaultJoinTimeout,
            @Value("${choreography.loops.max-iterations}") int defaultMaxRuns,
            StepContainerFactory containers,
            StoredPlans plans,
            Conditions conditions,
            MessagePublisher publisher,
            CompensationPublisher compensationPublisher,
            CompensationTrigger compensations,
            JoinStateMachine joins,
            JoinDeadlines deadlines,
            ServiceTable catalog,
            DiscoveryRegistration registration) {
        var hosted = services.stream().map(String::strip).filter(service -> !service.isEmpty()).toList();
        return new ServiceHost(hosted, new ServiceHost.Dependencies(bootstrapServers, partitions, containers, plans,
                conditions, defaultJoinTimeout, defaultMaxRuns, publisher, compensationPublisher, compensations, joins, deadlines,
                catalog, registration));
    }
}
