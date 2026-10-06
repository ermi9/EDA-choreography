package com.eda.choreography.infra.kafka;

import com.eda.choreography.domain.compensation.CompensationPublisher;
import com.eda.choreography.domain.compensation.CompensationRequest;
import com.eda.choreography.domain.compensation.CompensationTrigger;
import com.eda.choreography.domain.join.JoinTimeoutNotices;
import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.step.MessagePublisher;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Kafka clients for choreography messages. Spring Boot 4 only auto-configures Kafka through
 * {@code spring-boot-kafka}, which this project does not use, so the clients are declared here.
 * Nothing connects until a message is sent or a step container starts.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    @Bean
    ProducerFactory<String, ChoreographyMessage> choreographyProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        return new DefaultKafkaProducerFactory<>(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers),
                new StringSerializer(),
                MessageWireFormat.serializer());
    }

    @Bean
    KafkaTemplate<String, ChoreographyMessage> choreographyKafkaTemplate(
            ProducerFactory<String, ChoreographyMessage> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    ProducerFactory<String, CompensationRequest> compensationProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        return new DefaultKafkaProducerFactory<>(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers),
                new StringSerializer(),
                MessageWireFormat.serializer());
    }

    @Bean
    KafkaTemplate<String, CompensationRequest> compensationKafkaTemplate(
            ProducerFactory<String, CompensationRequest> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    CompensationPublisher compensationPublisher(
            KafkaTemplate<String, CompensationRequest> requests,
            KafkaTemplate<String, ChoreographyMessage> messages,
            @Value("${choreography.kafka.compensated-topic}") String compensatedTopic,
            @Value("${choreography.kafka.compensation-failed-topic}") String failedTopic) {
        return new KafkaCompensationPublisher(requests, messages, compensatedTopic, failedTopic);
    }

    @Bean
    CompensationTrigger compensationTrigger(CompensationPublisher publisher) {
        return new CompensationTrigger(publisher);
    }

    @Bean
    MessagePublisher messagePublisher(
            KafkaTemplate<String, ChoreographyMessage> template,
            @Value("${choreography.kafka.completed-topic}") String completedTopic) {
        return new KafkaMessagePublisher(template, completedTopic);
    }

    @Bean
    JoinTimeoutNotices joinTimeoutNotices(KafkaTemplate<String, ChoreographyMessage> template) {
        return new KafkaJoinTimeoutNotices(template);
    }

    @Bean
    ConsumerFactory<String, ChoreographyMessage> choreographyConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        return new DefaultKafkaConsumerFactory<>(
                consumerProperties(bootstrapServers), new StringDeserializer(), MessageWireFormat.deserializer());
    }

    @Bean
    ConsumerFactory<String, CompensationRequest> compensationConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        return new DefaultKafkaConsumerFactory<>(
                consumerProperties(bootstrapServers),
                new StringDeserializer(),
                MessageWireFormat.deserializer(CompensationRequest.class));
    }

    @Bean
    StepContainerFactory stepContainerFactory(
            ConsumerFactory<String, ChoreographyMessage> messages,
            ConsumerFactory<String, CompensationRequest> requests,
            @Value("${choreography.compensation.retry-for}") Duration compensationRetryFor) {
        return new StepContainerFactory(messages, requests, compensationRetryFor);
    }

    /**
     * A step that joins late, or a new consumer group, starts from the earliest unconsumed
     * message: requests already waiting on a step's topic must not be skipped. Offsets are
     * committed by the listener containers, never automatically.
     */
    private static Map<String, Object> consumerProperties(String bootstrapServers) {
        return Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    }
}
