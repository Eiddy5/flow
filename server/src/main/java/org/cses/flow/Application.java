package org.cses.flow;

import io.micronaut.runtime.Micronaut;

public class Application {

    public static void main(String[] args) {
        Micronaut.build(args)
            .mainClass(Application.class)
            .defaultEnvironments("flow-standalone")
            .start();
    }
}
