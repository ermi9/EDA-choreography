package com.eda.choreography.infra.flows;

import com.eda.choreography.domain.resolution.ServiceTable;
import com.eda.choreography.domain.step.MessagePublisher;
import com.eda.choreography.infra.bpmn.BpmnPlanner;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The flow store in Redis, the plans read from it, and the folder of flows loaded at startup. */
@Configuration(proxyBeanMethods = false)
public class FlowsConfig {

    @Bean
    BpmnPlanner bpmnPlanner() {
        return new BpmnPlanner();
    }

    @Bean
    FlowStore flowStore(StringRedisTemplate redis) {
        return new RedisFlowStore(redis);
    }

    @Bean
    StoredPlans storedPlans(FlowStore store, BpmnPlanner planner) {
        return new StoredPlans(store, planner);
    }

    @Bean
    Flows flows(FlowStore store, StoredPlans plans, BpmnPlanner planner, ServiceTable catalog, MessagePublisher publisher) {
        return new Flows(store, plans, planner, catalog, publisher);
    }

    /** Off with {@code choreography.flows.load-folder=false}, as in the context test without Redis. */
    @Bean
    @ConditionalOnProperty(name = "choreography.flows.load-folder", havingValue = "true", matchIfMissing = true)
    ApplicationRunner flowFolderLoader(Flows flows, @Value("${choreography.flows.folder}") Path folder) {
        return arguments -> FlowFolder.load(folder, flows);
    }
}
