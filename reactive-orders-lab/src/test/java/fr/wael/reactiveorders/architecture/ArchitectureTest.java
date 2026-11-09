package fr.wael.reactiveorders.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Duration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Le DDD reste vérifiable : une règle écrite dans le README ne suffit pas. */
@AnalyzeClasses(packages = "fr.wael.reactiveorders", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsIndependentOfInfrastructure = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "reactor..", "com.mongodb..",
                    "org.bson..", "org.apache.kafka..", "..adapter..", "..application..")
            .because("les règles métier doivent rester des objets Java testables sans Spring ni base");

    @ArchTest
    static final ArchRule applicationUsesPortsInsteadOfAdapters = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..")
            .because("l’application orchestre des ports ; la configuration branche leurs adaptateurs");

    @ArchTest
    static final ArchRule productionCodeMustNotBlockReactor = noClasses()
            .should().callMethod(Mono.class, "block")
            .orShould().callMethod(Mono.class, "block", Duration.class)
            .orShould().callMethod(Flux.class, "blockFirst")
            .orShould().callMethod(Flux.class, "blockFirst", Duration.class)
            .orShould().callMethod(Flux.class, "blockLast")
            .orShould().callMethod(Flux.class, "blockLast", Duration.class)
            .because("bloquer un event loop casse le modèle ; les tests peuvent attendre, pas le code de production");
}
