package com.claimflow.common.openapi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The error statuses an endpoint can return beyond the 400/500 every endpoint has, e.g.
 * {@code @ApiErrors({404, 409})}. OpenApiConfig turns each into a documented response with the
 * shared ApiError schema and a standard description, so controllers stay short and consistent.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiErrors {

    int[] value();
}
