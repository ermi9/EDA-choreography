package com.eda.choreography.infra.plan;

import com.eda.choreography.domain.plan.Conditions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The condition language choices are written in. */
@Configuration(proxyBeanMethods = false)
public class PlanConfig {

    @Bean
    Conditions conditions() {
        return new SpelConditions();
    }
}
