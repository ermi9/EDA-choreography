package com.eda.choreography.infra.flows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.plan.InvalidPlanException;
import com.eda.choreography.domain.resolution.ServiceEvent;
import com.eda.choreography.domain.resolution.ServiceTable;
import com.eda.choreography.domain.step.MessagePublisher;
import com.eda.choreography.infra.bpmn.BpmnPlanner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlowsTest {

    private final InMemoryFlowStore store = new InMemoryFlowStore();
    private final StoredPlans plans = new StoredPlans(store, new BpmnPlanner());
    private final ServiceTable catalog = new ServiceTable();
    private final List<String> publishedTo = new ArrayList<>();
    private final List<ChoreographyMessage> published = new ArrayList<>();
    private final Flows flows = new Flows(store, plans, new BpmnPlanner(), catalog, new MessagePublisher() {
        @Override
        public void publish(String service, ChoreographyMessage message) {
            publishedTo.add(service);
            published.add(message);
        }

        @Override
        public void publishCompleted(ChoreographyMessage message) {
            throw new AssertionError("a start is never complete");
        }
    });

    @Test
    void aFlowThatCanRunIsStoredAndSaysWhichServicesAreBoundLate() throws IOException {
        catalog.apply(new ServiceEvent(ServiceEvent.STATUS_CHANGED, "assess", "healthy", 1, "assess.in", "assess.compensate"));

        var defined = flows.define("loan", fixture("loan"));

        assertThat(store.flows).containsKey("loan");
        assertThat(defined.plan().start()).isEqualTo("assess");
        assertThat(defined.bindings().bound()).containsExactly("assess");
        assertThat(defined.bindings().lateBound()).containsExactlyInAnyOrder("review", "approve");
    }

    @Test
    void aFlowThatCannotRunIsRejectedAndNotStored() {
        assertThatThrownBy(() -> flows.define("broken", "<not-bpmn/>")).isInstanceOf(InvalidPlanException.class);
        assertThatThrownBy(() -> flows.define("no spaces please", "<x/>")).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.flows).isEmpty();
    }

    @Test
    void startingAFlowHandsItsFirstStepTheRequestAndReturnsTheInstance() throws IOException {
        flows.define("sale", fixture("notify-twice"));

        var correlationId = flows.start("sale", Map.of("order", "A-1"));

        assertThat(correlationId).startsWith("sale-");
        assertThat(publishedTo).containsExactly("notify");
        assertThat(published).singleElement().satisfies(message -> {
            assertThat(message.correlationId()).isEqualTo(correlationId);
            assertThat(message.flowName()).isEqualTo("sale");
            assertThat(message.input()).isEqualTo(Map.of("order", "A-1"));
            assertThat(message.step()).isEqualTo("Task_buyer");
            assertThat(message.trace()).isEmpty();
        });
    }

    @Test
    void aFlowThatIsNotStoredCannotStart() {
        assertThatThrownBy(() -> flows.start("unknown", Map.of())).isInstanceOf(Flows.UnknownFlowException.class);
    }

    @Test
    void aStoredFlowIsTranslatedOnceUntilItsBpmnChanges() throws IOException {
        flows.define("loan", fixture("loan"));
        var first = plans.forFlow("loan").orElseThrow();

        assertThat(plans.forFlow("loan")).containsSame(first);

        flows.define("loan", fixture("loan").replace("name=\"review\"", "name=\"second-look\""));
        assertThat(plans.forFlow("loan").orElseThrow().services()).contains("second-look");
    }

    @Test
    void theFlowFolderDefinesEveryFlowThatCanRunAndLeavesOutTheRest(@TempDir Path folder) throws IOException {
        Files.writeString(folder.resolve("loan.bpmn"), fixture("loan"));
        Files.writeString(folder.resolve("broken.bpmn"), "<not-bpmn/>");
        Files.writeString(folder.resolve("notes.txt"), "not a flow");

        assertThat(FlowFolder.load(folder, flows)).isEqualTo(1);
        assertThat(store.names()).containsExactly("loan");
    }

    private static String fixture(String name) throws IOException {
        try (var in = FlowsTest.class.getResourceAsStream("/bpmn/" + name + ".bpmn")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
