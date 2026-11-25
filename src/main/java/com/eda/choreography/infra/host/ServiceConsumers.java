package com.eda.choreography.infra.host;

import com.eda.choreography.domain.compensation.CompensationAction;
import com.eda.choreography.domain.compensation.CompensationPublisher;
import com.eda.choreography.domain.compensation.CompensationRunner;
import com.eda.choreography.domain.compensation.CompensationTrigger;
import com.eda.choreography.domain.join.ForkSkips;
import com.eda.choreography.domain.join.JoinDeadlines;
import com.eda.choreography.domain.join.JoinStateMachine;
import com.eda.choreography.domain.plan.Conditions;
import com.eda.choreography.domain.plan.PlannedJoinWaits;
import com.eda.choreography.domain.plan.PlannedRoutes;
import com.eda.choreography.domain.plan.Plans;
import com.eda.choreography.domain.resolution.ResolutionSource;
import com.eda.choreography.domain.step.JoinRunner;
import com.eda.choreography.domain.step.MessagePublisher;
import com.eda.choreography.domain.step.ServiceRunner;
import com.eda.choreography.domain.step.StepAction;
import com.eda.choreography.domain.step.StepRunner;
import com.eda.choreography.infra.kafka.StepContainerFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Starts what one service needs to take part in choreographies, whatever does its work: its step
 * and join on its input topic and its compensator on its compensation topic, routed by the stored
 * plans. The topics are created first, since discovery only names them.
 */
public class ServiceConsumers {

    private final Dependencies dependencies;
    private final PlannedRoutes routes;
    private final PlannedJoinWaits waits;

    /** What the consumers are built from. */
    public record Dependencies(
            String bootstrapServers,
            int partitions,
            StepContainerFactory containers,
            Plans plans,
            Conditions conditions,
            Duration defaultJoinTimeout,
            int defaultMaxRuns,
            MessagePublisher publisher,
            CompensationPublisher compensationPublisher,
            CompensationTrigger compensations,
            JoinStateMachine joins,
            JoinDeadlines deadlines,
            ForkSkips forks,
            ResolutionSource catalog) {}

    public ServiceConsumers(Dependencies dependencies) {
        this.dependencies = dependencies;
        this.routes = new PlannedRoutes(dependencies.plans(), dependencies.conditions(), dependencies.defaultMaxRuns());
        this.waits = new PlannedJoinWaits(dependencies.plans(), dependencies.defaultJoinTimeout());
    }

    /** Starts the service's containers; the caller stops them. */
    public List<MessageListenerContainer> start(String service, StepAction action, CompensationAction undo) {
        createTopics(service);
        var step = new StepRunner(service, action, routes, dependencies.publisher(), dependencies.compensations(),
                dependencies.catalog()::isLive, dependencies.forks());
        var join = new JoinRunner(step, waits, dependencies.joins(), dependencies.deadlines(),
                dependencies.compensations(), Clock.systemUTC());
        var compensator = new CompensationRunner(
                service, undo, dependencies.joins(), dependencies.compensationPublisher());
        List<MessageListenerContainer> containers = List.of(
                dependencies.containers().create(new ServiceRunner(step, join, waits::isJoin)),
                dependencies.containers().create(compensator));
        containers.forEach(MessageListenerContainer::start);
        return containers;
    }

    private void createTopics(String service) {
        try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, dependencies.bootstrapServers()))) {
            var topics = Stream.of(service + ".in", service + ".compensate")
                    .map(topic -> new NewTopic(topic, dependencies.partitions(), (short) 1))
                    .toList();
            for (var created : admin.createTopics(topics).values().values()) {
                try {
                    created.get();
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) {
                        throw e;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while creating the topics of " + service, e);
        } catch (Exception e) {
            throw new IllegalStateException("could not create the topics of " + service, e);
        }
    }
}
