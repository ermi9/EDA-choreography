package com.eda.choreography.infra.kafka;

import com.eda.choreography.domain.message.ChoreographyMessage;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/**
 * How a {@link ChoreographyMessage}, or a compensation request carrying one, looks on a Kafka
 * topic: plain JSON of the record's fields.
 *
 * <p>The JSON schema is the contract. No Java type header is written or read, so a service
 * built from another codebase can take part with its own DTO. Unknown fields are ignored
 * (Jackson 3's default, pinned by a test), so the schema can grow additively.
 */
final class MessageWireFormat {

    private MessageWireFormat() {
    }

    static <T> Serializer<T> serializer() {
        return new JacksonJsonSerializer<T>().noTypeInfo();
    }

    /**
     * A record that is not JSON, or that breaks the message's own invariants, comes out as
     * {@code null} with the failure in a header instead of throwing from the poll loop. The
     * listener container then skips it rather than redelivering it forever.
     */
    static Deserializer<ChoreographyMessage> deserializer() {
        return deserializer(ChoreographyMessage.class);
    }

    static <T> Deserializer<T> deserializer(Class<T> type) {
        return new ErrorHandlingDeserializer<>(new JacksonJsonDeserializer<>(type).ignoreTypeHeaders());
    }
}
