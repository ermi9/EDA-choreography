package com.eda.choreography.infra.participant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ParticipantContractTest {

    @Test
    void aDocumentWithBothOperationsIsAParticipantWhateverItsPathParameterIsCalled() {
        assertThat(ParticipantContract.missingFrom("""
                {"paths": {"/choreography/steps/{step}": {"post": {}}, "/choreography/undo/{name}": {"post": {}}}}"""))
                .isEmpty();
    }

    @Test
    void namesTheOperationsThatAreMissing() {
        assertThat(ParticipantContract.missingFrom("""
                {"paths": {"/choreography/steps/{step}": {"post": {}}, "/choreography/undo/{step}": {"get": {}}}}"""))
                .containsExactly("POST /choreography/undo/{step}");
        assertThat(ParticipantContract.missingFrom("{}")).hasSize(2);
        assertThat(ParticipantContract.missingFrom("openapi: 3.0.3")).containsExactly("an OpenAPI document in JSON");
    }
}
