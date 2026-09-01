package org.cses.flow.controller;

import lombok.Getter;
import lombok.Setter;
import org.paas.json.SerializableObject;

/**
 * HTTP protocol models shared by resource-specific controllers.
 */
public class ApiModels {

    private ApiModels() {
    }

    @Getter
    @Setter
    public static class ErrorView extends SerializableObject {

        private String message;

        public ErrorView(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }
}
