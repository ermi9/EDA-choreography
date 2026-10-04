package com.eda.choreography.infra.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.message.ChoreographyMessage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.SerializationUtils;
import tools.jackson.databind.json.JsonMapper;

class MessageWireFormatTest {

    private static final String TOPIC = "B.in";

    private final ChoreographyMessage message = ChoreographyMessage.start("order-42", "checkout", Map.of("sku", "X-1", "quantity", 10))
            .recordStep("A", Map.of("quantity", 10))
            .recordStep("B", Map.of("amount", 30, "lines", List.of(Map.of("sku", "X-1", "express", true))));

    @Test
    void aMessageSurvivesTheWireUnchanged() {
        var headers = new RecordHeaders();
        var bytes = MessageWireFormat.serializer().serialize(TOPIC, headers, message);

        assertThat(MessageWireFormat.deserializer().deserialize(TOPIC, headers, bytes)).isEqualTo(message);
    }

    @Test
    void writesNoJavaTypeHeaders() {
        // The schema is the contract, not a class name: consumers in other codebases must not need ours.
        var headers = new RecordHeaders();
        MessageWireFormat.serializer().serialize(TOPIC, headers, message);

        assertThat(headers.toArray()).isEmpty();
    }

    @Test
    void writesTheSchemaFieldNames() {
        var bytes = MessageWireFormat.serializer().serialize(TOPIC, new RecordHeaders(), message);
        var json = JsonMapper.builder().build().readTree(bytes);

        assertThat(json.propertyNames()).containsExactlyInAnyOrder("correlationId", "flowName", "input", "trace", "results");
        assertThat(json.get("trace").get(0).propertyNames()).containsExactlyInAnyOrder(
                "id", "parents", "correlationId", "stepId", "outcome", "resultRef");
        assertThat(json.get("trace").get(1).get("parents").get(0).asString())
                .isEqualTo(json.get("trace").get(0).get("id").asString());
        var reserveResult = json.get("results").get(json.get("trace").get(0).get("resultRef").asString());
        assertThat(reserveResult.isObject()).isTrue();
        assertThat(reserveResult.get("quantity").asInt()).isEqualTo(10);
    }

    @Test
    void ignoresFieldsAddedByALaterSchemaVersion() {
        var json = """
                {"correlationId":"order-42","flowName":"checkout","input":{},"trace":[],"results":{},"addedLater":{"x":1}}
                """;

        var read = MessageWireFormat.deserializer()
                .deserialize(TOPIC, new RecordHeaders(), json.getBytes(StandardCharsets.UTF_8));

        assertThat(read).isEqualTo(ChoreographyMessage.start("order-42", "checkout", Map.of()));
    }

    @Test
    void aMessageThatCannotBeReadIsFlaggedInsteadOfThrown() {
        // A poison record must not wedge the consumer in a redelivery loop.
        var headers = new RecordHeaders();
        var notJson = "not json".getBytes(StandardCharsets.UTF_8);

        assertThat(MessageWireFormat.deserializer().deserialize(TOPIC, headers, notJson)).isNull();
        assertThat(headers.lastHeader(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER)).isNotNull();
    }

    @Test
    void aMessageThatBreaksTheDomainRulesIsFlaggedToo() {
        var headers = new RecordHeaders();
        var orphanResult = """
                {"correlationId":"order-42","flowName":"checkout","input":{},"trace":[],"results":{"ghost":{"quantity":10}}}
                """.getBytes(StandardCharsets.UTF_8);

        assertThat(MessageWireFormat.deserializer().deserialize(TOPIC, headers, orphanResult)).isNull();
        assertThat(headers.lastHeader(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER)).isNotNull();
    }
}
