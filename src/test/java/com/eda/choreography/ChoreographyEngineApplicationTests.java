package com.eda.choreography;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.step.MessagePublisher;
import com.eda.choreography.infra.kafka.KafkaMessagePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The application context wires up without a broker or Redis reachable. */
@SpringBootTest
class ChoreographyEngineApplicationTests {

    @Autowired
    MessagePublisher publisher;

    @Test
    void contextLoadsWithTheKafkaPublisherBehindTheDomainPort() {
        assertThat(publisher).isInstanceOf(KafkaMessagePublisher.class);
    }
}
