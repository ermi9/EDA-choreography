package com.eda.choreography.infra.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eda.choreography.infra.AbstractInfraIT;
import com.eda.choreography.infra.flows.Flows;
import com.eda.choreography.infra.participant.StubParticipant.Answer;
import com.eda.choreography.infra.participant.StubParticipant.Call;
import com.eda.choreography.infra.web.AuthoringApi;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * HTTP services that implement the contract take part in a flow over the real broker and Redis:
 * announced on discovery's stream while the engine runs, taken up, called and undone.
 */
@SpringBootTest(properties = {
        "choreography.participants.enabled=true",
        "choreography.participants.call-timeout=1s",
        "choreography.participants.look-every=200ms"})
@DirtiesContext
class ParticipantFlowIT extends AbstractInfraIT {

    @Autowired
    Flows flows;

    @Autowired
    AuthoringApi api;

    @Autowired
    Participants participants;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String stock = "stock-" + suffix;
    private final String pay = "pay-" + suffix;
    private final String flow = "two-" + suffix;
    private final StubParticipant stockService = new StubParticipant();
    private final StubParticipant payService = new StubParticipant();

    @BeforeEach
    void plugInBothServicesWhileTheEngineRuns() {
        stockService.answers = call -> Answer.of(200, "{\"left\": 9}");
        payService.answers = call -> Answer.of(200, "{\"charged\": 30}");
        plugIn(stock, stockService);
        plugIn(pay, payService);
        await().atMost(Duration.ofSeconds(30)).until(() -> participants.services().containsAll(List.of(stock, pay)));
        flows.define(flow, """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                  <bpmn:process id="p" isExecutable="true">
                    <bpmn:startEvent id="s"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:task id="t1" name="%s"><bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing></bpmn:task>
                    <bpmn:task id="t2" name="%s"><bpmn:incoming>f2</bpmn:incoming><bpmn:outgoing>f3</bpmn:outgoing></bpmn:task>
                    <bpmn:endEvent id="e"><bpmn:incoming>f3</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="t1"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="t1" targetRef="t2"/>
                    <bpmn:sequenceFlow id="f3" sourceRef="t2" targetRef="e"/>
                  </bpmn:process>
                </bpmn:definitions>""".formatted(stock, pay));
    }

    @AfterEach
    void close() {
        stockService.close();
        payService.close();
    }

    @Test
    void aFlowRunsAcrossServicesThatOnlyImplementTheContract() {
        var run = flows.start(flow, Map.of("sku", "pen"));

        assertThat(awaitStatus(run, "COMPLETED").toString()).contains("charged=30");
        assertThat(stockService.calls).hasSize(1);
        assertThat(payService.calls).singleElement().satisfies(call -> assertThat(call.body()).contains("\"left\":9"));
    }

    @Test
    void aRefusedStepIsNotRetriedNorUndoneAndTheStepBeforeItIsUndoneUnderItsKey() {
        payService.answers = call -> Answer.of(422, "{\"reason\": \"no funds\"}");

        awaitStatus(flows.start(flow, Map.of()), "COMPENSATED");

        assertThat(payService.calls).hasSize(1);
        assertThat(stockService.undos()).extracting(Call::key)
                .containsExactly(stockService.steps().get(0).key());
    }

    @Test
    void aPassingErrorIsRetriedUnderTheSameKey() {
        var attempts = new AtomicInteger();
        payService.answers = call -> attempts.incrementAndGet() < 3 ? Answer.of(503, "") : Answer.of(200, "{}");

        awaitStatus(flows.start(flow, Map.of()), "COMPLETED");

        assertThat(payService.steps()).extracting(Call::key).hasSize(3).containsOnly(payService.steps().get(0).key());
        assertThat(stockService.undos()).isEmpty();
    }

    @Test
    void aStepThatNeverAnswersMayHaveHappenedSoItIsUndoneToo() {
        payService.answers = call -> call.undo() ? Answer.of(200, "{}") : new Answer(200, "{}", 1_500);

        var run = awaitStatus(flows.start(flow, Map.of()), "COMPENSATED");

        assertThat(run.toString()).contains("outcome=UNCERTAIN");
        assertThat(payService.undos()).extracting(Call::key).containsExactly(payService.steps().get(0).key());
        assertThat(stockService.undos()).hasSize(1);
    }

    @Test
    void anUndoThatKeepsFailingIsParkedAndHoldsWhatComesAfterUntilItIsTriedAgain() {
        payService.answers = call -> call.undo() ? Answer.of(500, "down") : Answer.of(500, "");

        var run = flows.start(flow, Map.of());
        awaitStatus(run, "PARKED");
        assertThat(stockService.undos()).isEmpty();

        payService.answers = call -> Answer.of(200, "{}");
        assertThat(api.undoAgain(run).getStatusCode().value()).isEqualTo(202);
        awaitStatus(run, "COMPENSATED");
        assertThat(stockService.undos()).hasSize(1);
        assertThat(api.undoAgain(run).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void aServiceThatDoesNotDeclareTheContractIsLeftAlone() {
        var other = "other-" + suffix;
        DISCOVERY.registerHealthyWithSpec(other, stockService.url() + "/missing");
        DISCOVERY.awaitCaughtUp(serviceTable, other);
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                .until(() -> !participants.services().contains(other));
    }

    private void plugIn(String service, StubParticipant stub) {
        DISCOVERY.registerHealthyAt(service, stub.url(), stub.url() + "/openapi.json");
    }

    private Map<String, Object> awaitStatus(String run, String status) {
        await().atMost(Duration.ofSeconds(45)).until(() -> status.equals(api.runOf(run).get("status")));
        return api.runOf(run);
    }
}
