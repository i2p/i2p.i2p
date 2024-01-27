package org.rrd4j.core;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Description of a {@link RrdBackendFactory}
 * 
 * @author Fabrice Bacchella
 * @since 3.4
 *
 */
@Documented
@Retention(RUNTIME)
@Target(TYPE)
public @interface RrdBackendAnnotation {
    boolean DEFAULT_CACHING_ALLOWED = true;
    String name();
    boolean cachingAllowed() default DEFAULT_CACHING_ALLOWED;
    String scheme() default "";
    boolean shouldValidateHeader();
}
