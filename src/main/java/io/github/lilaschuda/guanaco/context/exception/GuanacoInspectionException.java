package io.github.lilaschuda.guanaco.context.exception;

/**
 * Thrown when a {@link io.github.lilaschuda.guanaco.api.GuanacoRoute}-annotated processor's declared route topology
 * can't be extracted — e.g. its {@link io.github.lilaschuda.guanaco.api.Processor}
 * type parameter isn't a proper sealed {@link io.github.lilaschuda.guanaco.api.RouteOutcome}
 * hierarchy, or its permitted subtypes can't be resolved via reflection. Always a
 * startup-time failure — topology is inspected once, before any route is wired.
 */
public class GuanacoInspectionException extends RuntimeException {
    /**
     * Constructs a GuanacoInspectionException with a specific message.
     *
     * @param message description of the topology inspection failure
     */
    public GuanacoInspectionException(String message) { super(message); }

    /**
     * Constructs a GuanacoInspectionException with a specific message and cause.
     *
     * @param message description of the topology inspection failure
     * @param cause the underlying reflection or class-loading failure
     */
    public GuanacoInspectionException(String message, Throwable cause) { super(message, cause); }
}