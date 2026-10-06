package com.eda.choreography;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.compensation.CompensationPublisher;
import com.eda.choreography.domain.join.JoinStateStore;
import com.eda.choreography.domain.step.MessagePublisher;
import com.eda.choreography.infra.kafka.KafkaCompensationPublisher;
import com.eda.choreography.infra.kafka.KafkaMessagePublisher;
import com.eda.choreography.infra.redis.RedisJoinStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The application context wires up without a broker or Redis reachable. The join sweeper is
 * off, since it would reach for Redis every second.
 */
@SpringBootTest(properties = "choreography.join.sweep-enabled=false")
class ChoreographyEngineApplicationTests {

    @Autowired
    MessagePublisher publisher;

    @Autowired
    CompensationPublisher compensationPublisher;

    @Autowired
    JoinStateStore joinStateStore;

    @Test
    void contextLoadsWithTheKafkaPublisherBehindTheDomainPort() {
        assertThat(publisher).isInstanceOf(KafkaMessagePublisher.class);
    }

    @Test
    void compensationGoesOverKafkaAndJoinStateLivesInRedis() {
        assertThat(compensationPublisher).isInstanceOf(KafkaCompensationPublisher.class);
        assertThat(joinStateStore).isInstanceOf(RedisJoinStateStore.class);
    }
}
