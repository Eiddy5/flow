package org.cses.flow.processor;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Generates a class index without loading or instantiating Input definitions. */
@SupportedAnnotationTypes("com.fasterxml.jackson.annotation.JsonTypeName")
public class InputTypeProcessor extends AbstractProcessor {
    private Map<String, Element> inputs = new TreeMap<>();

    /** @return the source version supported by the host compiler */
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /**
     * Collects annotated concrete Input subtypes and writes one deterministic index.
     * @param annotations annotations selected by the compiler, not modified
     * @param round current compilation round
     * @return false so Jackson and Micronaut processors can also inspect annotations
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        TypeElement base = processingEnv.getElementUtils()
            .getTypeElement("org.cses.flow.core.domains.flows.Input");
        if (base == null) {
            return false;
        }
        for (TypeElement annotation : annotations) {
            for (Element element : round.getElementsAnnotatedWith(annotation)) {
                if (element instanceof TypeElement type
                    && !type.getModifiers().contains(Modifier.ABSTRACT)
                    && processingEnv.getTypeUtils().isSubtype(
                        processingEnv.getTypeUtils().erasure(type.asType()),
                        processingEnv.getTypeUtils().erasure(base.asType()))) {
                    inputs.put(processingEnv.getElementUtils().getBinaryName(type).toString(), type);
                }
            }
        }
        if (round.processingOver() && !round.errorRaised() && !inputs.isEmpty()) {
            try (Writer writer = processingEnv.getFiler().createResource(
                StandardLocation.CLASS_OUTPUT, "", "META-INF/flow/inputs",
                inputs.values().toArray(Element[]::new)).openWriter()) {
                for (String name : inputs.keySet()) {
                    writer.write(name + "\n");
                }
            } catch (IOException exception) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Cannot generate Flow Input index: " + exception.getMessage());
            }
        }
        return false;
    }
}
