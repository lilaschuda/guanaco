/**
 * Guanaco's routes.yaml/routes.json configuration model -- plain Jackson-
 * deserialized POJOs, populated from user-authored YAML or JSON with no
 * enforced-required fields.
 *
 * <p>{@code @NullMarked}: same non-null-by-default convention as the
 * {@code api} package, but the audit behind these annotations follows a
 * different rule here. These are mutable JavaBeans with no constructor
 * validation -- a field is only genuinely non-null if its own setter
 * defends against a null argument (or the field is a primitive). A
 * declared default value at the field's declaration is not sufficient on
 * its own: Jackson (or any caller) can still overwrite that default with
 * an explicit null through an unguarded setter.
 */
@NullMarked
package io.github.lilaschuda.guanaco.config;

import org.jspecify.annotations.NullMarked;
