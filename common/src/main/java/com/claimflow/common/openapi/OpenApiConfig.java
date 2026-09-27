package com.claimflow.common.openapi;

import com.claimflow.common.correlation.CorrelationId;
import com.claimflow.common.error.ApiError;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * One OpenAPI definition style for every service (ADR-0030):
 * <ul>
 *   <li>group {@code v1} = everything under {@code /api/v1/**} (actuator and internals excluded),
 *       published at {@code /v3/api-docs/v1}</li>
 *   <li>{@code info.version} = the contract version of v1 (semantic: minor for additions, a new
 *       {@code /api/v2} + major for breaking changes)</li>
 *   <li>server = the API gateway, the only public entry point</li>
 *   <li>shared {@code ApiError} schema, the {@code X-Correlation-ID} header and the standard error
 *       responses on every operation</li>
 * </ul>
 * Services set {@code claimflow.api.title} and {@code claimflow.api.description}.
 */
@Configuration
@ConditionalOnClass(name = "org.springdoc.core.models.GroupedOpenApi")
public class OpenApiConfig {

    public static final String GROUP = "v1";
    public static final String PATHS = "/api/v1/**";

    private static final String CORRELATION_PARAM = "CorrelationId";
    private static final String ERROR_SCHEMA_REF = "#/components/schemas/ApiError";

    @Bean
    public OpenAPI claimFlowOpenApi(@Value("${claimflow.api.title:ClaimFlow API}") String title,
                                    @Value("${claimflow.api.description:}") String description,
                                    @Value("${claimflow.api.version:1.0.0}") String version,
                                    @Value("${claimflow.api.server-url:http://localhost:8000}") String serverUrl) {
        Components components = new Components()
                .addParameters(CORRELATION_PARAM, new HeaderParameter()
                        .name(CorrelationId.HEADER)
                        .required(false)
                        .description("Optional. Traces the request across services and Kafka; generated if absent "
                                + "and always returned in the response header and error bodies.")
                        .schema(new StringSchema().example("7f92a1c2-8e32-4e12-b7d2-91a3f8c4d111")));
        // ApiError AND its nested FieldViolation (read() would return only the top-level type)
        Map<String, Schema> errorSchemas = ModelConverters.getInstance().readAll(ApiError.class);
        errorSchemas.forEach(components::addSchemas);

        return new OpenAPI()
                .info(new Info()
                        .title(title)
                        .version(version)
                        .description(description + "\n\nAPI version **v1** (contract " + version + "). "
                                + "Breaking changes get a new `/api/v2`; additions keep v1 and bump the minor version.")
                        .license(new License().name("Learning project, not for production use")))
                .servers(List.of(new Server().url(serverUrl).description("API gateway (local)")))
                .components(components);
    }

    @Bean
    public GroupedOpenApi v1Api() {
        return GroupedOpenApi.builder()
                .group(GROUP)
                .displayName("v1")
                .pathsToMatch(PATHS)
                .addOperationCustomizer((operation, handlerMethod) -> {
                    boolean hasCorrelationParam = operation.getParameters() != null && operation.getParameters()
                            .stream().anyMatch(p -> CorrelationId.HEADER.equalsIgnoreCase(p.getName()));
                    if (!hasCorrelationParam) {
                        operation.addParametersItem(new Parameter().$ref("#/components/parameters/" + CORRELATION_PARAM));
                    }
                    // Every operation can fail these ways (GlobalExceptionHandler); document them once, consistently.
                    addErrorResponse(operation.getResponses(), 400);
                    addErrorResponse(operation.getResponses(), 500);
                    // ...plus the endpoint-specific ones declared with @ApiErrors
                    ApiErrors declared = handlerMethod.getMethodAnnotation(ApiErrors.class);
                    if (declared != null) {
                        for (int status : declared.value()) {
                            addErrorResponse(operation.getResponses(), status);
                        }
                    }
                    return operation;
                })
                .build();
    }

    /** Standard meaning of each error status in ClaimFlow (docs/api-design.md). */
    private static final Map<Integer, String> ERROR_DESCRIPTIONS = Map.of(
            400, "Invalid request: malformed body, bad parameter, or failed validation (see `violations`)",
            404, "The resource in the URL does not exist",
            405, "HTTP method not supported on this path",
            409, "Conflicts with the current state: invalid state transition, duplicate, or a concurrent update",
            412, "`If-Match` does not match the current version: reload (GET) and retry",
            422, "Well-formed but breaks a business rule",
            500, "Unexpected error (details are logged, never returned)");

    private static void addErrorResponse(io.swagger.v3.oas.models.responses.ApiResponses responses, int status) {
        String code = String.valueOf(status);
        if (!responses.containsKey(code)) {
            responses.addApiResponse(code, new ApiResponse()
                    .description(ERROR_DESCRIPTIONS.getOrDefault(status, "Error"))
                    .content(new Content().addMediaType("application/json",
                            new MediaType().schema(new Schema<>().$ref(ERROR_SCHEMA_REF)))));
        }
    }
}
