package com.eda.choreography.infra;

import com.eda.choreography.domain.join.JoinDeadlineSweeper;
import com.eda.choreography.domain.join.JoinDeadlines;
import com.eda.choreography.domain.join.JoinTimeoutNotices;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Sweeps for timed-out joins on a fixed delay. The deadlines are in Redis and the notices go
 * over Kafka, so this sits above both adapters. Every running engine sweeps; the sweeper is
 * safe to run more than once at a time (see {@link JoinDeadlineSweeper}).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "choreography.join.sweep-enabled", havingValue = "true", matchIfMissing = true)
public class JoinTimeoutConfig {

    private static final int BATCH_SIZE = 100;

    @Bean
    JoinDeadlineSweeper joinDeadlineSweeper(JoinDeadlines deadlines, JoinTimeoutNotices notices) {
        return new JoinDeadlineSweeper(deadlines, notices, Clock.systemUTC(), BATCH_SIZE);
    }

    @Bean
    ScheduledSweep scheduledJoinSweep(JoinDeadlineSweeper sweeper) {
        return new ScheduledSweep(sweeper);
    }

    /** A sweep that throws is logged by the scheduler and tried again on the next tick. */
    static final class ScheduledSweep {

        private final JoinDeadlineSweeper sweeper;

        ScheduledSweep(JoinDeadlineSweeper sweeper) {
            this.sweeper = sweeper;
        }

        @Scheduled(fixedDelayString = "${choreography.join.sweep-interval}")
        void sweep() {
            sweeper.sweep();
        }
    }
}
