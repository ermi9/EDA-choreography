package com.eda.choreography.infra.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.resolution.ServiceEvent;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

class ServiceEventFormatTest {

    @Test
    void readsAnEventAsDiscoveryWritesItAndIgnoresTheFieldsItDoesNotUse() {
        // Every field of discovery's published event, with a type header naming discovery's class.
        var json = """
                {"type":"STATUS_CHANGED","serviceName":"payment","url":"http://payment:8080",
                 "openapiUrl":"http://payment:8080/v3/api-docs","status":"healthy",
                 "timestamp":"2026-10-14T09:30:00","generation":7,
                 "inputTopic":"payment.in","compensationTopic":"payment.compensate"}
                """;
        var headers = new RecordHeaders();
        headers.add("__TypeId__", "com.eda.discovery.model.ServiceEvent".getBytes(StandardCharsets.UTF_8));

        var event = ServiceEventsListener.deserializer()
                .deserialize("service-events", headers, json.getBytes(StandardCharsets.UTF_8));

        assertThat(event).isEqualTo(
                new ServiceEvent("STATUS_CHANGED", "payment", "healthy", 7, "payment.in", "payment.compensate"));
    }

    @Test
    void aTypeOrStatusItDoesNotKnowStillReads() {
        var json = "{\"type\":\"SERVICE_RENAMED\",\"serviceName\":\"payment\",\"status\":\"draining\",\"generation\":1}";

        var event = ServiceEventsListener.deserializer()
                .deserialize("service-events", new RecordHeaders(), json.getBytes(StandardCharsets.UTF_8));

        assertThat(event).isEqualTo(new ServiceEvent("SERVICE_RENAMED", "payment", "draining", 1, null, null));
    }

    @Test
    void aRecordThatIsNotAnEventComesOutAsNullInsteadOfThrowing() {
        var event = ServiceEventsListener.deserializer()
                .deserialize("service-events", new RecordHeaders(), "not json".getBytes(StandardCharsets.UTF_8));

        assertThat(event).isNull();
    }
}
