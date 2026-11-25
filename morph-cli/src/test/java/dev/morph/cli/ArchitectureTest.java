package dev.morph.cli;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Boundaries and privacy rules that must hold for the production code, checked on the compiled classes. */
class ArchitectureTest {

    private static JavaClasses morph;

    @BeforeAll
    static void load() {
        morph = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("dev.morph");
        // Guards against rules that pass because nothing was imported.
        for (String name : List.of("dev.morph.contracts.ConversionProvider", "dev.morph.engine.MorphEngine",
                "dev.morph.provider.image.ImageProvider", "dev.morph.provider.pdf.PdfProvider",
                "dev.morph.application.MorphRuntime", "dev.morph.local.LocalServer", "dev.morph.cli.Main")) {
            assertTrue(morph.contain(name), name);
        }
    }

    @Test
    void contractsDependOnNothing() {
        classes().that().resideInAPackage("dev.morph.contracts..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("dev.morph.contracts..", "java..")
                .check(morph);
    }

    @Test
    void theEngineKnowsOnlyTheContracts() {
        classes().that().resideInAPackage("dev.morph.engine..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("dev.morph.engine..", "dev.morph.contracts..", "java..")
                .check(morph);
        noClasses().that().resideInAnyPackage("dev.morph.engine..", "dev.morph.contracts..")
                .should().dependOnClassesThat().resideInAnyPackage("java.awt..", "java.net..", "java.sql..")
                .check(morph);
    }

    @Test
    void providersKnowNeitherTheEngineNorTheFrontEnds() {
        noClasses().that().resideInAPackage("dev.morph.provider..")
                .should().dependOnClassesThat().resideInAnyPackage("dev.morph.engine..", "dev.morph.application..",
                        "dev.morph.local..", "dev.morph.cli..")
                .check(morph);
    }

    @Test
    void frontEndsNeverReachIntoProviders() {
        noClasses().that().resideInAnyPackage("dev.morph.application..", "dev.morph.local..", "dev.morph.cli..")
                .should().dependOnClassesThat().resideInAPackage("dev.morph.provider..")
                .check(morph);
        noClasses().that().resideInAPackage("dev.morph.local..")
                .should().dependOnClassesThat().resideInAPackage("dev.morph.cli..")
                .check(morph);
    }

    @Test
    void frameworksStayInTheirLayer() {
        noClasses().that().resideOutsideOfPackage("dev.morph.local..")
                .should().dependOnClassesThat().resideInAnyPackage("io.javalin..", "org.eclipse.jetty..",
                        "jakarta..", "kotlin..")
                .check(morph);
        noClasses().that().resideOutsideOfPackage("dev.morph.cli..")
                .should().dependOnClassesThat().resideInAPackage("picocli..")
                .check(morph);
        noClasses().that().resideOutsideOfPackage("dev.morph.provider.pdf..")
                .should().dependOnClassesThat().resideInAPackage("org.apache.pdfbox..")
                .check(morph);
        noClasses().that().haveNameNotMatching("dev\\.morph\\.provider\\.image\\.raster\\.WebpEncoder.*")
                .should().dependOnClassesThat().resideInAPackage("dev.matrixlab..")
                .check(morph);
    }

    /** Nothing in Morph can start a connection: not even the local server, which only accepts them. */
    @Test
    void nothingOpensConnections() {
        noClasses().should().dependOnClassesThat(
                        resideInAnyPackage("java.net.http..", "javax.net..", "java.rmi..", "javax.naming..",
                                "com.sun.net..")
                        .or(nameMatching("java\\.net\\.(Socket|ServerSocket|DatagramSocket|MulticastSocket"
                                + "|URLConnection|HttpURLConnection|URLClassLoader|InetAddress|InetSocketAddress)"))
                        .or(nameMatching("java\\.nio\\.channels\\.(Asynchronous)?(Server)?Socket.*"))
                        .or(nameMatching("java\\.nio\\.channels\\.Datagram.*")))
                .check(morph);
        noClasses().should().callMethod(URL.class, "openConnection")
                .orShould().callMethod(URL.class, "openStream")
                .check(morph);
    }

    @Test
    void childProcessesAreStartedInASinglePlace() {
        noClasses().that().doNotHaveFullyQualifiedName("dev.morph.cli.BrowserLauncher")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                .orShould().callMethod(Runtime.class, "exec", String.class)
                .orShould().callMethod(Runtime.class, "exec", String[].class)
                .check(morph);
    }

    @Test
    void nothingIsLogged() {
        noClasses().that().haveNameNotMatching("dev\\.morph\\.provider\\.pdf\\.PdfDocuments") // silences PDFBox
                .should().dependOnClassesThat(resideInAnyPackage("java.util.logging..", "org.slf4j..",
                        "org.apache.commons.logging.."))
                .check(morph);
        noClasses().that(resideInAPackage("dev.morph..").and(
                        resideInAnyPackage("dev.morph.contracts..", "dev.morph.engine..", "dev.morph.provider..",
                                "dev.morph.application..", "dev.morph.local..")))
                .should().accessField(System.class, "out")
                .orShould().accessField(System.class, "err")
                .orShould().callMethod(Throwable.class, "printStackTrace")
                .check(morph);
    }

    @Test
    void everyMorphClassIsJava21Bytecode() throws IOException, URISyntaxException {
        int checked = 0;
        for (var type : morph) {
            URL resource = type.getSource().orElseThrow().getUri().toURL();
            try (InputStream in = resource.openStream(); DataInputStream data = new DataInputStream(in)) {
                data.readInt();
                data.readUnsignedShort();
                assertTrue(data.readUnsignedShort() == 65, type.getName());
                checked++;
            }
        }
        assertTrue(checked > 50);
    }
}
