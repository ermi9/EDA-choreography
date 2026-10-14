package com.eda.choreography.infra.web;

import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;

/**
 * The run outcomes the API reports, fed by a listener on the completed and compensated topics.
 * The listener has a group of its own, so every engine sees every outcome. It can be switched off
 * with {@code choreography.runs.listen-enabled=false}, which the context test without a broker
 * does.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig {

    private static final int KEPT_OUTCOMES = 1_000;

    @Bean
    RunOutcomes runOutcomes() {
        return new RunOutcomes(KEPT_OUTCOMES);
    }

    @Bean
    @ConditionalOnProperty(name = "choreography.runs.listen-enabled", havingValue = "true", matchIfMissing = true)
    ConcurrentMessageListenerContainer<String, ChoreographyMessage> runOutcomesListener(
            ConsumerFactory<String, ChoreographyMessage> messages,
            RunOutcomes outcomes,
            @Value("${choreography.kafka.completed-topic}") String completedTopic,
            @Value("${choreography.kafka.compensated-topic}") String compensatedTopic,
            @Value("${spring.application.name}") String applicationName) {
        var properties = new ContainerProperties(completedTopic, compensatedTopic);
        properties.setGroupId(applicationName + "-runs-" + UUID.randomUUID());
        properties.setMessageListener((MessageListener<String, ChoreographyMessage>) record -> {
            if (record.value() != null) {
                outcomes.record(record.topic().equals(compensatedTopic)
                        ? RunOutcomes.Status.COMPENSATED
                        : RunOutcomes.Status.COMPLETED, record.value());
            }
        });
        var container = new ConcurrentMessageListenerContainer<>(messages, properties);
        container.setBeanName("run-outcomes");
        return container;
    }
}
