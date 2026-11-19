package org.flow.http.plugins;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.flow.core.serializers.JacksonMapper;
import org.flow.core.services.PluginService;
import org.flow.plugin.core.debug.Return;
import org.flow.plugin.core.log.Log;
import org.junit.jupiter.api.Test;
import org.paas.diagnostic.service.impl.ServerServiceImpl;
import org.paas.response.wrapper.NettyJsonHandlerLoggingFilter;
import org.paas.session.DefaultSession;
import org.paas.session.DefaultSessionFactory;
import org.paas.session.SessionFactory;
import org.paas.session.User;
import org.paas.websocket.index.WebsocketGrid;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.host", value = "127.0.0.1")
@Property(name = "micronaut.server.port", value = "-1")
@Property(name = "flow.http.plugins.enabled", value = "true")
@Property(name = "paas.session.enabled", value = "false")
class PluginControllerTest {
    private static final String ROUTE = "/flow/plugins";
    private static final String LOG_TYPE = Log.class.getName();

    @Inject
    EmbeddedServer server;

    @Inject
    PluginService pluginService;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void hostRoutesDiscoverableTypesAndKeepsThePaaSResponseUnwrapped() throws Exception {
        assertTrue(server.getApplicationContext().containsBean(PluginController.class));
        assertTrue(server.getApplicationContext().containsBean(NettyJsonHandlerLoggingFilter.class));

        JsonNode tasks = body(get(ROUTE + "?category=tasks", "allowed"));
        assertTrue(tasks.isArray());
        List<String> names = new ArrayList<>();
        tasks.forEach(task -> {
            assertEquals("tasks", task.path("category").asText());
            names.add(task.path("type").asText());
        });
        assertTrue(names.contains(LOG_TYPE));
        assertEquals(names.stream().distinct().sorted().toList(), names);
        assertEquals(0, body(get(ROUTE + "?category=unknown", "allowed")).size());
        assertEquals(400, get(ROUTE + "?category=%20", "allowed").statusCode());
    }

    @Test
    void describesConfigurationExamplesAndOutputsForTheRegisteredTypes() throws Exception {
        JsonNode log = body(get(ROUTE + "/" + LOG_TYPE, "allowed"));
        assertEquals(LOG_TYPE, log.at("/metadata/type").asText());
        assertEquals("Emit log entries from a flow.", log.at("/metadata/title").asText());
        JsonNode schema = log.path("schema");
        assertTrue(schema.at("/properties/message").isObject());
        assertTrue(schema.at("/properties/id").isObject());
        assertSchemaRefsResolve(schema, schema);
        assertEquals("yaml", log.at("/examples/0/lang").asText());
        assertTrue(log.at("/examples/0/full").asBoolean());
        assertTrue(log.at("/examples/0/code").isArray());
        assertTrue(log.path("outputs").isEmpty());

        JsonNode output = body(get(ROUTE + "/" + Return.class.getName(), "allowed")).at("/outputs/0");
        assertEquals("value", output.path("name").asText());
        assertEquals("string", output.path("type").asText());
    }

    @Test
    void distinguishesLoadedVersionsFromUnknownTypes() throws Exception {
        JsonNode versions = body(get(ROUTE + "/" + LOG_TYPE + "/versions", "allowed"));
        assertEquals(JacksonMapper.ofJson().valueToTree(pluginService.versions(LOG_TYPE)), versions);
        assertEquals(404, get(ROUTE + "/unknown.Plugin", "allowed").statusCode());
        assertEquals(404, get(ROUTE + "/unknown.Plugin/versions", "allowed").statusCode());
        assertEquals(400, get(ROUTE + "/%20/versions", "allowed").statusCode());
    }

    @Test
    void everyDiscoverableTypeHasAConfigurationSchema() throws Exception {
        for (JsonNode plugin : body(get(ROUTE, "allowed"))) {
            String type = plugin.path("type").asText();
            HttpResponse<String> response = get(ROUTE + "/" + type, "allowed");
            assertEquals(200, response.statusCode(), type);
            JsonNode detail = body(response);
            assertEquals(type, detail.at("/metadata/type").asText());
            JsonNode schema = detail.path("schema");
            assertTrue(schema.isObject(), type);
            assertSchemaRefsResolve(schema, schema);
        }
    }

    @Test
    void hostAuthorizationCoversAllPluginRoutes() throws Exception {
        for (String route : List.of(ROUTE, ROUTE + "/" + LOG_TYPE, ROUTE + "/" + LOG_TYPE + "/versions")) {
            assertEquals(401, get(route, null).statusCode());
            assertEquals(403, get(route, "denied").statusCode());
        }
        try (ApplicationContext engineOnly = ApplicationContext.run(Map.of("paas.session.enabled", false))) {
            assertFalse(engineOnly.containsBean(PluginController.class));
        }
    }

    private HttpResponse<String> get(String path, String identity) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(server.getURI().resolve(path)).GET();
        if (identity != null) {
            request.header("X-Test-Identity", identity);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode body(HttpResponse<String> response) throws IOException {
        assertEquals(200, response.statusCode(), response::body);
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        return JacksonMapper.ofJson().readTree(response.body());
    }

    private static void assertSchemaRefsResolve(JsonNode schema, JsonNode node) {
        String ref = node.path("$ref").asText();
        if (ref.startsWith("#/")) {
            assertFalse(schema.at(ref.substring(1)).isMissingNode(), ref);
        }
        node.forEach(child -> assertSchemaRefsResolve(schema, child));
    }

    @Factory
    static class HostBeans {
        @Singleton
        SessionFactory<DefaultSession, User> sessionFactory() {
            return new DefaultSessionFactory();
        }

        @Singleton
        @Replaces(ServerServiceImpl.class)
        ServerServiceImpl<?, ?> diagnosticService() {
            return mock(ServerServiceImpl.class);
        }

        @Singleton
        @Replaces(WebsocketGrid.class)
        WebsocketGrid websocketGrid() {
            return mock(WebsocketGrid.class);
        }
    }

    @Filter({ROUTE, ROUTE + "/**"})
    static class HostAuthorization implements HttpServerFilter {
        @Override
        public org.reactivestreams.Publisher<io.micronaut.http.MutableHttpResponse<?>> doFilter(
            io.micronaut.http.HttpRequest<?> request, ServerFilterChain chain) {
            String identity = request.getHeaders().get("X-Test-Identity");
            if (identity == null) {
                return Mono.just(io.micronaut.http.HttpResponse.unauthorized());
            }
            if (!identity.equals("allowed")) {
                return Mono.just(io.micronaut.http.HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN));
            }
            return chain.proceed(request);
        }
    }
}
