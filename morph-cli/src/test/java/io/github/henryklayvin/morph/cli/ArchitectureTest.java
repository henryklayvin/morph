package io.github.henryklayvin.morph.cli;

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

    private static final String ROOT = "io.github.henryklayvin.morph";

    private static JavaClasses morph;

    @BeforeAll
    static void load() {
        morph = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages(ROOT);
        // Guards against rules that pass because nothing was imported.
        for (String name : List.of("contracts.ConversionProvider", "engine.MorphEngine", "provider.image.ImageProvider",
                "provider.pdf.PdfProvider", "application.MorphRuntime", "local.LocalServer", "cli.Main")) {
            assertTrue(morph.contain(ROOT + "." + name), name);
        }
    }

    /** A Morph package and its subpackages, in ArchUnit notation. */
    private static String pkg(String name) {
        return ROOT + "." + name + "..";
    }

    /** A regular expression that matches exactly one fully qualified Morph class name. */
    private static String classRegex(String name) {
        return (ROOT + "." + name).replace(".", "\\.");
    }

    @Test
    void contractsDependOnNothing() {
        classes().that().resideInAPackage(pkg("contracts"))
                .should().onlyDependOnClassesThat().resideInAnyPackage(pkg("contracts"), "java..")
                .check(morph);
    }

    @Test
    void theEngineKnowsOnlyTheContracts() {
        classes().that().resideInAPackage(pkg("engine"))
                .should().onlyDependOnClassesThat().resideInAnyPackage(pkg("engine"), pkg("contracts"), "java..")
                .check(morph);
        noClasses().that().resideInAnyPackage(pkg("engine"), pkg("contracts"))
                .should().dependOnClassesThat().resideInAnyPackage("java.awt..", "java.net..", "java.sql..")
                .check(morph);
    }

    @Test
    void providersKnowNeitherTheEngineNorTheFrontEnds() {
        noClasses().that().resideInAPackage(pkg("provider"))
                .should().dependOnClassesThat()
                .resideInAnyPackage(pkg("engine"), pkg("application"), pkg("local"), pkg("cli"))
                .check(morph);
    }

    @Test
    void frontEndsNeverReachIntoProviders() {
        noClasses().that().resideInAnyPackage(pkg("application"), pkg("local"), pkg("cli"))
                .should().dependOnClassesThat().resideInAPackage(pkg("provider"))
                .check(morph);
        noClasses().that().resideInAPackage(pkg("local"))
                .should().dependOnClassesThat().resideInAPackage(pkg("cli"))
                .check(morph);
    }

    @Test
    void frameworksStayInTheirLayer() {
        noClasses().that().resideOutsideOfPackage(pkg("local"))
                .should().dependOnClassesThat().resideInAnyPackage("io.javalin..", "org.eclipse.jetty..",
                        "jakarta..", "kotlin..")
                .check(morph);
        noClasses().that().resideOutsideOfPackage(pkg("cli"))
                .should().dependOnClassesThat().resideInAPackage("picocli..")
                .check(morph);
        noClasses().that().resideOutsideOfPackage(pkg("provider.pdf"))
                .should().dependOnClassesThat().resideInAPackage("org.apache.pdfbox..")
                .check(morph);
        noClasses().that().haveNameNotMatching(classRegex("provider.image.raster.WebpEncoder") + ".*")
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
        noClasses().that().doNotHaveFullyQualifiedName(ROOT + ".cli.BrowserLauncher")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                .orShould().callMethod(Runtime.class, "exec", String.class)
                .orShould().callMethod(Runtime.class, "exec", String[].class)
                .check(morph);
    }

    @Test
    void nothingIsLogged() {
        noClasses().that().haveNameNotMatching(classRegex("provider.pdf.PdfDocuments")) // silences PDFBox
                .should().dependOnClassesThat(resideInAnyPackage("java.util.logging..", "org.slf4j..",
                        "org.apache.commons.logging.."))
                .check(morph);
        noClasses().that(resideInAPackage(ROOT + "..").and(resideInAnyPackage(pkg("contracts"), pkg("engine"),
                        pkg("provider"), pkg("application"), pkg("local"))))
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
