package com.eda.choreography.infra.host;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Runs the configured demo services in this process (see {@link ServiceConsumers}). Each service
 * is then registered with discovery (see {@link DiscoveryRegistration}), which starts probing its
 * health and announces it.
 */
public class ServiceHost implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceHost.class);

    private final List<String> services;
    private final ServiceConsumers consumers;
    private final DiscoveryRegistration registration;
    private final List<MessageListenerContainer> containers = new ArrayList<>();
    private volatile boolean running;

    public ServiceHost(List<String> services, ServiceConsumers consumers, DiscoveryRegistration registration) {
        this.services = List.copyOf(services);
        this.consumers = consumers;
        this.registration = registration;
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
        for (var service : services) {
            containers.addAll(consumers.start(service, new DemoAction(service), (entry, instance) ->
                    LOG.info("demo: {} undoes {} of {}", service, entry.stepId(), instance.correlationId())));
        }
        LOG.info("hosting services {}", services);
        registration.registerInBackground(services);
        running = true;
    }

    @Override
    public void stop() {
        registration.deregister(services);
        containers.forEach(MessageListenerContainer::stop);
        containers.clear();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
