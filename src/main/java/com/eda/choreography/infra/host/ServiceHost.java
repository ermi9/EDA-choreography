package com.eda.choreography.infra.host;

import com.eda.choreography.domain.compensation.CompensationPublisher;
import com.eda.choreography.domain.compensation.CompensationRunner;
import com.eda.choreography.domain.compensation.CompensationTrigger;
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
import com.eda.choreography.domain.step.StepRunner;
import com.eda.choreography.infra.kafka.StepContainerFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Runs the configured services in this process: for each, its step and join on its input topic
 * and its compensator on its compensation topic, routed by the stored plans. The topics are
 * created first, since discovery only names them. Each service is then registered with discovery
 * (see {@link DiscoveryRegistration}), which starts probing its health and announces it.
 */
public class ServiceHost implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceHost.class);

    private final List<String> services;
    private final Dependencies dependencies;
    private final List<MessageListenerContainer> containers = new ArrayList<>();
    private volatile boolean running;

    /** What the hosted services are built from. */
    public record Dependencies(
            String bootstrapServers,
            int partitions,
            StepContainerFactory containers,
            Plans plans,
            Conditions conditions,
            Duration defaultJoinTimeout,
            MessagePublisher publisher,
            CompensationPublisher compensationPublisher,
            CompensationTrigger compensations,
            JoinStateMachine joins,
            JoinDeadlines deadlines,
            ResolutionSource catalog,
            DiscoveryRegistration registration) {}

    public ServiceHost(List<String> services, Dependencies dependencies) {
        this.services = List.copyOf(services);
        this.dependencies = dependencies;
    }

    public List<String> services() {
        return services;
    }

    @Override
    public void start() {
        if (services.isEmpty()) {
            running = true;
            return;
        }
        createTopics();
        var routes = new PlannedRoutes(dependencies.plans(), dependencies.conditions());
        var waits = new PlannedJoinWaits(dependencies.plans(), dependencies.defaultJoinTimeout());
        for (var service : services) {
            var step = new StepRunner(service, new DemoAction(service), routes,
                    dependencies.publisher(), dependencies.compensations(), dependencies.catalog()::isLive);
            var join = new JoinRunner(step, waits, dependencies.joins(), dependencies.deadlines(),
                    dependencies.compensations(), Clock.systemUTC());
            containers.add(dependencies.containers().create(new ServiceRunner(step, join, waits::isJoin)));
            containers.add(dependencies.containers().create(new CompensationRunner(service,
                    (entry, instance) -> LOG.info("demo: {} undoes {} of {}", service, entry.stepId(), instance.correlationId()),
                    dependencies.joins(), dependencies.compensationPublisher())));
        }
        containers.forEach(MessageListenerContainer::start);
        LOG.info("hosting services {}", services);
        dependencies.registration().registerInBackground(services);
        running = true;
    }

    @Override
    public void stop() {
        dependencies.registration().deregister(services);
        containers.forEach(MessageListenerContainer::stop);
        containers.clear();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void createTopics() {
        try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, dependencies.bootstrapServers()))) {
            var existing = admin.listTopics().names().get();
            var missing = services.stream()
                    .flatMap(service -> Set.of(service + ".in", service + ".compensate").stream())
                    .filter(topic -> !existing.contains(topic))
                    .map(topic -> new NewTopic(topic, dependencies.partitions(), (short) 1))
                    .toList();
            admin.createTopics(missing).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("could not create the topics of " + services, e);
        }
    }
}
