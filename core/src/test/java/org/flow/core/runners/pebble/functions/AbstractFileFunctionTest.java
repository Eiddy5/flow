package org.flow.core.runners.pebble.functions;

import java.net.URI;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;

import jakarta.inject.Inject;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@FlowTest
public class AbstractFileFunctionTest {

    @Inject
    ReadFileFunction readFileFunction;

    @Test
    void namespaceFromURI() {
        String namespace1 = readFileFunction
            .extractNamespace(URI.create("flow:///demo/simple-write-oss/executions/4Tnd2zrWGoHGrufwyt738j/tasks/write/2FOeylkRr5tktwIQqFh56w/18316959863401460785.txt"));
        assertThat(namespace1).isEqualTo("demo");

        String namespace2 = readFileFunction
            .extractNamespace(URI.create("flow:///org/flow/tests/simple-write-oss/executions/4Tnd2zrWGoHGrufwyt738j/tasks/write/2FOeylkRr5tktwIQqFh56w/18316959863401460785.txt"));
        assertThat(namespace2).isEqualTo("org.flow.tests");

        assertThrows(
            IllegalArgumentException.class,
            () -> readFileFunction.extractNamespace(URI.create("flow:///simple-write-oss/executions/4Tnd2zrWGoHGrufwyt738j/tasks/write/2FOeylkRr5tktwIQqFh56w/18316959863401460785.txt"))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> readFileFunction.extractNamespace(URI.create("flow:///executions/4Tnd2zrWGoHGrufwyt738j/tasks/write/2FOeylkRr5tktwIQqFh56w/18316959863401460785.txt"))
        );
    }
}
