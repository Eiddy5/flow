package org.flow.core.http.client;

import java.io.Serial;

import org.flow.core.http.HttpRequest;
import org.flow.core.http.HttpResponse;

import jakarta.annotation.Nullable;
import lombok.Getter;

@Getter
public class HttpClientResponseException extends HttpClientException {
    @Serial
    private static final long serialVersionUID = 1L;

    protected transient final HttpRequest request;

    @Nullable
    protected transient HttpResponse<?> response;

    public HttpClientResponseException(String message, HttpResponse<?> response) {
        super(message);
        this.request = response.getRequest();
        this.response = response;
    }

    public HttpClientResponseException(String message, HttpResponse<?> response, final Throwable cause) {
        super(message, cause);
        this.request = response.getRequest();
        this.response = response;
    }
}
