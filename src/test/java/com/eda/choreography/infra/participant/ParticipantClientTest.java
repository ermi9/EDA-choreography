package com.eda.choreography.infra.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.step.StepRejectedException;
import com.eda.choreography.domain.step.UncertainOutcomeException;
import com.eda.choreography.infra.participant.StubParticipant.Answer;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ParticipantClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final StubParticipant stub = new StubParticipant();
    private final ParticipantClient client = client(stub.url() + "/");
    private final ChoreographyMessage message = ChoreographyMessage.start("order-42", "checkout", Map.of("sku", "pen"))
            .recordStep("inventory", Map.of("reserved", 2)).addressedTo("Task_pay");

    @AfterEach
    void close() {
        stub.close();
    }

    @Test
    void aStepIsPostedWithItsKeyInputAndEarlierResultsAndItsAnswerIsTheResult() {
        stub.answers = call -> Answer.of(200, "{\"charged\": 30}");

        assertThat(client.execute(message)).isEqualTo(Map.of("charged", 30));

        assertThat(stub.calls).singleElement().satisfies(call -> {
            assertThat(call.undo()).isFalse();
            assertThat(call.step()).isEqualTo("Task_pay");
            assertThat(call.key()).isEqualTo(message.entryIdFor("Task_pay"));
            var body = JSON.readTree(call.body());
            assertThat(body.at("/correlationId").asString()).isEqualTo("order-42");
            assertThat(body.at("/flowName").asString()).isEqualTo("checkout");
            assertThat(body.at("/step").asString()).isEqualTo("Task_pay");
            assertThat(body.at("/visit").asInt()).isEqualTo(1);
            assertThat(body.at("/input/sku").asString()).isEqualTo("pen");
            assertThat(body.at("/results/inventory/reserved").asInt()).isEqualTo(2);
        });
    }

    @Test
    void anEmptyAnswerIsAnEmptyResult() {
        stub.answers = call -> Answer.of(204, "");
        assertThat(client.execute(message)).isEmpty();
    }

    @Test
    void aRefusalOrAnAlreadyUndoneRunIsRejectedForGood() {
        for (var status : new int[] {422, 409}) {
            stub.answers = call -> Answer.of(status, "out of stock");
            assertThatThrownBy(() -> client.execute(message))
                    .isInstanceOf(StepRejectedException.class).hasMessageContaining("out of stock");
        }
    }

    @Test
    void anErrorAnUnreadableResultOrNoAnswerLeavesTheOutcomeUncertain() {
        stub.answers = call -> Answer.of(500, "boom");
        assertThatThrownBy(() -> client.execute(message)).isInstanceOf(UncertainOutcomeException.class);
        stub.answers = call -> Answer.of(200, "[1]");
        assertThatThrownBy(() -> client.execute(message)).isInstanceOf(UncertainOutcomeException.class);
        stub.answers = call -> new Answer(200, "{}", 1_500);
        assertThatThrownBy(() -> client.execute(message)).isInstanceOf(UncertainOutcomeException.class);
        assertThatThrownBy(() -> client("http://localhost:1").execute(message))
                .isInstanceOf(UncertainOutcomeException.class);
        assertThatThrownBy(() -> client("not a url").execute(message)).isInstanceOf(UncertainOutcomeException.class);
    }

    @Test
    void anUndoNamesTheRunItUndoesAndCarriesWhatTheStepAnswered() {
        var done = message.recordStep("Task_pay", "payment", Map.of("charged", 30));

        client.undo(done.trace().get(1), done);

        assertThat(stub.calls).singleElement().satisfies(call -> {
            assertThat(call.undo()).isTrue();
            assertThat(call.key()).isEqualTo(message.entryIdFor("Task_pay"));
            assertThat(JSON.readTree(call.body()).at("/result/charged").asInt()).isEqualTo(30);
        });
    }

    @Test
    void anUncertainRunIsUndoneUnderTheKeyItsAttemptsHad() {
        var uncertain = message.recordUncertain("Task_pay", "payment");

        client.undo(uncertain.trace().get(1), uncertain);

        assertThat(stub.calls).singleElement().satisfies(call -> {
            assertThat(call.key()).isEqualTo(message.entryIdFor("Task_pay"));
            assertThat(JSON.readTree(call.body()).at("/result").isNull()).isTrue();
        });
    }

    @Test
    void anUndoThatIsNotConfirmedThrowsSoItIsTriedAgain() {
        var done = message.recordStep("Task_pay", "payment", Map.of());
        stub.answers = call -> Answer.of(422, "cannot");
        assertThatThrownBy(() -> client.undo(done.trace().get(1), done)).hasMessageContaining("422");
    }

    private static ParticipantClient client(String url) {
        return new ParticipantClient("payment", service -> Optional.of(url), Duration.ofSeconds(1), HttpClient.newHttpClient());
    }
}
