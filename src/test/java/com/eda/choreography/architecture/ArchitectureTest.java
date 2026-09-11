package com.eda.choreography.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The single most important design decision of the project, made executable: the domain
 * must not know about infrastructure. Keeping INC-1/INC-2 pure is what makes them testable
 * without a broker, and what lets INC-4 swap the state store without touching join logic.
 */
@AnalyzeClasses(packages = "com.eda.choreography", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainDoesNotDependOnInfra = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..infra..");

    @ArchTest
    static final ArchRule domainDoesNotDependOnFrameworks = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.apache.kafka..",
                    "org.springframework..",
                    "io.lettuce..",
                    "redis.clients..");
}
