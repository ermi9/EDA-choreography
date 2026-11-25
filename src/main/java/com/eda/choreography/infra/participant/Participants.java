package com.eda.choreography.infra.participant;

import com.eda.choreography.domain.resolution.ServiceTable;
import com.eda.choreography.infra.host.ServiceConsumers;
import com.eda.choreography.infra.web.ServiceSpecs;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Takes up every service that discovery reports healthy and whose OpenAPI document declares the
 * participant contract: reads its topics and calls it over HTTP. Nothing is configured per
 * service; one that registers while this runs is taken up within a couple of seconds.
 *
 * <p>A service that goes down or deregisters is kept. It is sent no new work, since it is not
 * live, but work and undo requests already on its topics still have to be carried out or fail.
 * After a restart it is taken up again once it is healthy; until then its topics wait.
 *
 * <p>Several engines can run this. They take up the same services and share each one's topics,
 * as consumers of the same group.
 */
public class Participants implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(Participants.class);
    /** Names discovery uses as topic names unchanged. */
    private static final Pattern TOPIC_SAFE = Pattern.compile("[A-Za-z0-9._-]+");

    private final boolean enabled;
    private final ServiceTable table;
    private final ServiceSpecs specs;
    private final ServiceConsumers consumers;
    private final Set<String> hostedHere;
    private final Duration callTimeout;
    private final Duration lookEvery;
    private final HttpClient http;

    private final Map<String, List<MessageListenerContainer>> taken = new HashMap<>();
    private final Map<String, String> lastRemark = new HashMap<>();
    private ScheduledExecutorService looking;
    private volatile boolean running;

    public Participants(
            boolean enabled,
            ServiceTable table,
            ServiceSpecs specs,
            ServiceConsumers consumers,
            List<String> hostedHere,
            Duration callTimeout,
            Duration lookEvery) {
        this.enabled = enabled;
        this.table = table;
        this.specs = specs;
        this.consumers = consumers;
        this.hostedHere = Set.copyOf(hostedHere);
        this.callTimeout = callTimeout;
        this.lookEvery = lookEvery;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(callTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** The services taken up so far. */
    public synchronized Set<String> services() {
        return Set.copyOf(taken.keySet());
    }

    @Override
    public void start() {
        running = true;
        if (!enabled) {
            return;
        }
        looking = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "participants");
            thread.setDaemon(true);
            return thread;
        });
        looking.scheduleWithFixedDelay(this::look, 0, lookEvery.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void look() {
        table.services().forEach((service, resolution) -> {
            if (!resolution.live() || hostedHere.contains(service) || services().contains(service)) {
                return;
            }
            try {
                consider(service);
            } catch (RuntimeException e) {
                remark(service, "could not be taken up: " + e);
            }
        });
    }

    private void consider(String service) {
        if (!TOPIC_SAFE.matcher(service).matches()) {
            remark(service, "has a name that cannot be a topic's; use letters, digits, '.', '_' and '-'");
            return;
        }
        if (specs.fetch(service) instanceof ServiceSpecs.Spec.Found found) {
            var missing = ParticipantContract.missingFrom(found.body());
            if (missing.isEmpty()) {
                takeUp(service);
            } else {
                remark(service, "is no participant; its OpenAPI document lacks " + missing);
            }
        } else {
            remark(service, "is no participant; its OpenAPI document cannot be fetched");
        }
    }

    private synchronized void takeUp(String service) {
        if (!running) {
            return;
        }
        var client = new ParticipantClient(service, table::url, callTimeout, http);
        taken.put(service, consumers.start(service, client, client));
        LOG.info("took up participant {} at {}", service, table.url(service).orElse("?"));
    }

    /** Said once, not every couple of seconds. */
    private void remark(String service, String text) {
        if (!text.equals(lastRemark.put(service, text))) {
            LOG.info("{} {}", service, text);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (looking != null) {
            looking.shutdownNow();
        }
        synchronized (this) {
            taken.values().forEach(containers -> containers.forEach(MessageListenerContainer::stop));
            taken.clear();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
