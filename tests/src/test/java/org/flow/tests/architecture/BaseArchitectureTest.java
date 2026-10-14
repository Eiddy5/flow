package org.flow.tests.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.*;

/**
 * Base architecture test with common rules that apply across all modules.
 * This class can be extended by specific modules to add their own rules while inheriting common constraints.
 */
@AnalyzeClasses(packages = "org.flow", importOptions = ImportOption.DoNotIncludeTests.class)
public class BaseArchitectureTest {

    @ArchTest
    static final ArchRule no_java_util_logging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule no_joda_time = NO_CLASSES_SHOULD_USE_JODATIME;

    @ArchTest
    static void test_same_package(JavaClasses classes) {
        // Annotations such as FlowTest are test infrastructure, not implementation tests.
        testClassesShouldResideInTheSamePackageAsImplementation().check(classes.that(
            DescribedPredicate.describe("non-annotation classes", javaClass -> !javaClass.isAnnotation())
        ));
    }

    @ArchTest
    static final ArchRule no_standard_stream = noClasses()
        .that().doNotBelongToAnyOf(org.flow.core.utils.ThreadUncaughtExceptionHandler.class)
        .should(ACCESS_STANDARD_STREAMS);

    @ArchTest
    public static final ArchRule no_production_use_of_awaitility = noClasses()
        .that().doNotBelongToAnyOf(org.flow.core.utils.Await.class, org.flow.core.junit.extensions.AbstractLoaderExtension.class)
        .should().dependOnClassesThat().resideInAPackage("org.awaitility")
        .because("you should not use it directly but use " + org.flow.core.utils.Await.class.getName() + " wrapper instead");
}
