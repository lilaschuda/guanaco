package io.github.lilaschuda.guanaco.testutils;

import io.github.lilaschuda.guanaco.context.GuanacoContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.mock.MockEndpoint;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Execution wrapper providing simplified template delivery and mock access
 * for Guanaco test suites.
 */
public final class GuanacoRuntimeEnvironment {

    private GuanacoContext context;
    private final ProducerTemplate producer;

    /**
     * Constructs a GuanacoRuntimeEnvironment wrapping the specified GuanacoContext.
     *
     * @param context the context to wrap and execute test operations against
     */
    public GuanacoRuntimeEnvironment(GuanacoContext context) {
        this.context = context;
        this.producer = context.createProducerTemplate();
    }

    /**
     * Looks up or provisions a standard mock endpoint for assertions.
     *
     * @param endpointUri the endpoint URI to convert to a mock endpoint
     * @return the resolved MockEndpoint instance
     */
    public MockEndpoint getMock(String endpointUri) {
        return context.getEndpoint(endpointUri, MockEndpoint.class);
    }

    /**
     * Sends a basic message body to the target endpoint.
     *
     * @param endpointUri the destination endpoint URI
     * @param body the payload body to send, or {@code null} to send a message with no body
     */
    public void send(String endpointUri, @Nullable Object body) {
        producer.sendBody(endpointUri, body);
    }

    /**
     * Sends a message body accompanied by a map of routing headers.
     * Uses wildcard tracking to prevent invariant generic compilation errors.
     *
     * @param endpointUri the destination endpoint URI
     * @param body the payload body to send, or {@code null} to send a message with no body
     * @param headers the message headers to attach
     */
    public void send(String endpointUri, @Nullable Object body, Map<String, ?> headers) {
        // Camel's underlying engine expects Map<String, Object>
        Map<String, Object> camelHeaders = new HashMap<>(headers);
        producer.sendBodyAndHeaders(endpointUri, body, camelHeaders);
    }

    /**
     * Sets the application context.
     *
     * @param context the GuanacoContext to assign
     */
    public void setApplicationContext(GuanacoContext context){
        this.context = context;
    }

    /**
     * Retrieves the application context.
     *
     * @return the active GuanacoContext
     */
    public GuanacoContext getApplicationContext(){
        return this.context;
    }

    /** Gracefully tears down the testing context and messaging producers. */
    public void shutdown() {
        try {
            producer.stop();
            context.stop();
        } catch (Exception e) {
            throw new RuntimeException("Failed to cleanly teardown test environment", e);
        }
    }
}