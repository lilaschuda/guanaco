package io.github.lilaschuda.guanaco.annotation;

import java.lang.annotation.*;

/**
 * Marks a class as a Guanaco route processor.
 * The annotated class must implement {@link org.guanaco.dsl.Processor}.
 *
 * The 'from' endpoint URI is declared in routes.yaml, not here,
 * keeping operational config out of compiled code.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface GuanacoRoute {

    /**
     * Optional human-readable name for this route.
     * Defaults to the simple class name if not specified.
     */
    String name() default "";
}
