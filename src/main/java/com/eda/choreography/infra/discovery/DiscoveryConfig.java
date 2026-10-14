package com.eda.choreography.infra.discovery;

import com.eda.choreography.domain.resolution.ServiceEvent;
import com.eda.choreography.domain.resolution.ServiceTable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

/**
 * The engine's table of services, kept current from discovery's {@code service-events} stream.
 * The listener starts with the application. It can be switched off with
 * {@code choreography.discovery.listen-enabled=false}, which the context test that runs without
 * a broker does.
 */
@Configuration(proxyBeanMethods = false)
public class DiscoveryConfig {

    @Bean
    ServiceTable serviceTable() {
        return new ServiceTable();
    }

    @Bean
    @ConditionalOnProperty(name = "choreography.discovery.listen-enabled", havingValue = "true", matchIfMissing = true)
    ConcurrentMessageListenerContainer<String, ServiceEvent> serviceEventsListener(
            ServiceTable table,
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${choreography.discovery.topic}") String topic,
            @Value("${spring.application.name}") String applicationName) {
        return ServiceEventsListener.create(
                ServiceEventsListener.consumerFactory(bootstrapServers), topic, applicationName, table);
    }
}
