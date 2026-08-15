package io.github.lilaschuda.guanaco.testutils;

import io.github.lilaschuda.guanaco.core.GuanacoContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.mock.MockEndpoint;

import java.util.HashMap;
import java.util.Map;

/**
 * Execution wrapper providing simplified template delivery and mock access 
 * for Guanaco test suites.
 */
public final class GuanacoRuntimeEnvironment {
    
    private GuanacoContext context;
    private final ProducerTemplate producer;

    public GuanacoRuntimeEnvironment(GuanacoContext context) {
        this.context = context;
        this.producer = context.createProducerTemplate();
    }

    /** Looks up or provisions a standard mock endpoint for assertions. */
    public MockEndpoint getMock(String endpointUri) {
        return context.getEndpoint(endpointUri, MockEndpoint.class);
    }

    /** Sends a basic message body to the target endpoint. */
    public void send(String endpointUri, Object body) {
        producer.sendBody(endpointUri, body);
    }

    /** 
     * Sends a message body accompanied by a map of routing headers.
     * Uses wildcard tracking to prevent invariant generic compilation errors.
     */
    public void send(String endpointUri, Object body, Map<String, ?> headers) {
        // Camel's underlying engine expects Map<String, Object>
        Map<String, Object> camelHeaders = new HashMap<>(headers);
        producer.sendBodyAndHeaders(endpointUri, body, camelHeaders);
    }

    public void setApplicationContext(GuanacoContext context){
        this.context = context;
    }
    
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