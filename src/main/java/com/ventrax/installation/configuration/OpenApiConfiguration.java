package com.ventrax.installation.configuration;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.*;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.*;
import io.swagger.v3.oas.models.security.*;
import io.swagger.v3.oas.models.tags.Tag;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.*;

@Configuration
public class OpenApiConfiguration {
    public record Document(
            String title,
            String version,
            String description,
            Map<String, String> tags,
            List<OperationDoc> operations) {}

    public record OperationDoc(
            String method,
            String path,
            String tag,
            String summary,
            String description,
            int success,
            String security) {}

    @Bean
    Document apiDocumentation() throws IOException {
        try (var stream = new ClassPathResource("openapi/operations.json").getInputStream()) {
            return new tools.jackson.databind.ObjectMapper().readValue(stream, Document.class);
        }
    }

    @Bean
    OpenAPI serviceOpenApi(Document documentation) {
        return new OpenAPI()
                .info(
                        new Info()
                                .title(documentation.title())
                                .version(documentation.version())
                                .description(documentation.description())
                                .license(new License().name("MIT")))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "bearerAuth",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                                .description(
                                                        "Current IAM JWT. The service verifies the"
                                                            + " active session and"
                                                            + " operation-specific ownership, role"
                                                            + " and meter/function permissions."
                                                            + " Roles: USER and SUPERADMIN."))
                                .addSecuritySchemes(
                                        "serviceAuth",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .description(
                                                        "Private service credential, independent of"
                                                            + " user JWTs. Internal routes must"
                                                            + " remain outside public gateway"
                                                            + " routing.")));
    }

    @Bean
    OpenApiCustomizer alignedContracts(Document documentation) {
        Map<String, OperationDoc> definitions = new LinkedHashMap<>();
        for (var definition : documentation.operations()) {
            if (definitions.put(definition.method() + " " + definition.path(), definition) != null)
                throw new IllegalStateException("Duplicate API documentation entry");
        }
        return api -> {
            api.setTags(
                    documentation.tags().entrySet().stream()
                            .map(e -> new Tag().name(e.getKey()).description(e.getValue()))
                            .toList());
            if (api.getPaths() == null) return;
            api.getPaths()
                    .forEach(
                            (path, item) ->
                                    item.readOperationsMap()
                                            .forEach(
                                                    (method, operation) -> {
                                                        var doc =
                                                                definitions.get(
                                                                        method.name() + " " + path);
                                                        if (doc == null)
                                                            throw new IllegalStateException(
                                                                    "Missing API documentation: "
                                                                            + method
                                                                            + " "
                                                                            + path);
                                                        operation.setTags(List.of(doc.tag()));
                                                        operation.setSummary(doc.summary());
                                                        operation.setDescription(doc.description());
                                                        operation.setSecurity(
                                                                doc.security().isEmpty()
                                                                        ? List.of()
                                                                        : List.of(
                                                                                new SecurityRequirement()
                                                                                        .addList(
                                                                                                doc
                                                                                                        .security())));
                                                        if (operation.getResponses() == null)
                                                            operation.setResponses(
                                                                    new ApiResponses());
                                                        String status =
                                                                Integer.toString(doc.success());
                                                        var success =
                                                                operation
                                                                        .getResponses()
                                                                        .get(status);
                                                        if (success == null)
                                                            success =
                                                                    operation
                                                                            .getResponses()
                                                                            .get("200");
                                                        if (success == null)
                                                            success = new ApiResponse();
                                                        for (String code :
                                                                new ArrayList<>(
                                                                        operation
                                                                                .getResponses()
                                                                                .keySet()))
                                                            if (code.startsWith("2")
                                                                    && !code.equals(status))
                                                                operation
                                                                        .getResponses()
                                                                        .remove(code);
                                                        success.setDescription(
                                                                doc.success() == 201
                                                                        ? "Resource created."
                                                                        : doc.success() == 202
                                                                                ? "Request"
                                                                                      + " accepted;"
                                                                                      + " asynchronous"
                                                                                      + " work or"
                                                                                      + " delivery"
                                                                                      + " may still"
                                                                                      + " be pending."
                                                                                : doc.success()
                                                                                                == 204
                                                                                        ? "Operation"
                                                                                              + " completed"
                                                                                              + " without"
                                                                                              + " a response"
                                                                                              + " body."
                                                                                        : "Operation"
                                                                                              + " completed"
                                                                                              + " successfully.");
                                                        if (doc.success() == 204)
                                                            success.setContent(null);
                                                        operation
                                                                .getResponses()
                                                                .addApiResponse(status, success);
                                                        if (!doc.security().isEmpty())
                                                            operation
                                                                    .getResponses()
                                                                    .putIfAbsent(
                                                                            "401",
                                                                            new ApiResponse()
                                                                                    .description(
                                                                                            doc.security()
                                                                                                            .equals(
                                                                                                                    "serviceAuth")
                                                                                                    ? "Missing"
                                                                                                          + " or invalid"
                                                                                                          + " private"
                                                                                                          + " service"
                                                                                                          + " credential."
                                                                                                    : "Missing,"
                                                                                                          + " expired,"
                                                                                                          + " revoked"
                                                                                                          + " or invalid"
                                                                                                          + " IAM session."));
                                                        if (doc.security().equals("bearerAuth"))
                                                            operation
                                                                    .getResponses()
                                                                    .putIfAbsent(
                                                                            "403",
                                                                            new ApiResponse()
                                                                                    .description(
                                                                                            "The current"
                                                                                                + " account"
                                                                                                + " does"
                                                                                                + " not have"
                                                                                                + " the required"
                                                                                                + " role,"
                                                                                                + " ownership"
                                                                                                + " or meter/function"
                                                                                                + " permission."));
                                                        if (path.equals("/api/v1/devices")
                                                                && method
                                                                        == PathItem.HttpMethod.GET)
                                                            success.setContent(
                                                                    json(
                                                                            new ArraySchema()
                                                                                    .items(
                                                                                            new Schema<>()
                                                                                                    .$ref(
                                                                                                            "#/components/schemas/DeviceView"))));
                                                        if (path.equals(
                                                                        "/api/v1/devices/{id}/associations")
                                                                && method
                                                                        == PathItem.HttpMethod.GET)
                                                            success.setContent(
                                                                    json(
                                                                            new ArraySchema()
                                                                                    .items(
                                                                                            new Schema<>()
                                                                                                    .$ref(
                                                                                                            "#/components/schemas/Association"))));
                                                        if (path.endsWith("/download")) {
                                                            Content content = new Content();
                                                            for (String mime :
                                                                    path.contains("/photos/")
                                                                            ? List.of(
                                                                                    "image/jpeg",
                                                                                    "image/png")
                                                                            : List.of(
                                                                                    "application/pdf",
                                                                                    "application/xml",
                                                                                    "application/zip"))
                                                                content.addMediaType(
                                                                        mime,
                                                                        new MediaType()
                                                                                .schema(
                                                                                        new StringSchema()
                                                                                                .format(
                                                                                                        "binary")));
                                                            success.setContent(content);
                                                        }
                                                        if (operation.getParameters() != null)
                                                            for (var parameter :
                                                                    operation.getParameters()) {
                                                                String name = parameter.getName();
                                                                if (parameter.getDescription()
                                                                        == null)
                                                                    parameter.setDescription(
                                                                            parameterDescription(
                                                                                    name));
                                                                if (parameter.getSchema() != null) {
                                                                    if (name.equals("page")
                                                                            || name.equals(
                                                                                    "offset"))
                                                                        parameter
                                                                                .getSchema()
                                                                                .setMinimum(
                                                                                        java.math
                                                                                                .BigDecimal
                                                                                                .ZERO);
                                                                    if (name.equals("size")
                                                                            || name.equals(
                                                                                    "limit")) {
                                                                        parameter
                                                                                .getSchema()
                                                                                .setMinimum(
                                                                                        java.math
                                                                                                .BigDecimal
                                                                                                .ONE);
                                                                        parameter
                                                                                .getSchema()
                                                                                .setMaximum(
                                                                                        java.math
                                                                                                .BigDecimal
                                                                                                .valueOf(
                                                                                                        100));
                                                                    }
                                                                    if (name.equals("format"))
                                                                        parameter
                                                                                .getSchema()
                                                                                .setEnum(
                                                                                        List.of(
                                                                                                "pdf",
                                                                                                "xml",
                                                                                                "cdr"));
                                                                }
                                                                if (name.equals(
                                                                        "objectReference")) {
                                                                    parameter.setDeprecated(true);
                                                                    parameter.setDescription(
                                                                            "Deprecated; omit this"
                                                                                + " parameter."
                                                                                + " Client-supplied"
                                                                                + " storage"
                                                                                + " references are"
                                                                                + " rejected.");
                                                                }
                                                                if (name.equals(
                                                                        "uploadedByAccountId")) {
                                                                    parameter.setDeprecated(true);
                                                                    parameter.setDescription(
                                                                            "Deprecated; the"
                                                                                + " uploader is"
                                                                                + " derived from"
                                                                                + " the authenticated"
                                                                                + " session. A"
                                                                                + " supplied value"
                                                                                + " must match that"
                                                                                + " identity.");
                                                                }
                                                            }
                                                        if (path.endsWith("/photos")
                                                                && method
                                                                        == PathItem.HttpMethod.POST
                                                                && operation.getRequestBody()
                                                                        != null) {
                                                            operation
                                                                    .getRequestBody()
                                                                    .setRequired(true);
                                                            operation
                                                                    .getRequestBody()
                                                                    .setDescription(
                                                                            "A real JPEG or PNG"
                                                                                + " multipart file"
                                                                                + " is required."
                                                                                + " Maximum 10 MiB"
                                                                                + " and 25 million"
                                                                                + " decoded pixels."
                                                                                + " Storage"
                                                                                + " references and"
                                                                                + " uploader"
                                                                                + " identity are"
                                                                                + " resolved by the"
                                                                                + " service.");
                                                            var media =
                                                                    operation
                                                                            .getRequestBody()
                                                                            .getContent()
                                                                            .get(
                                                                                    "multipart/form-data");
                                                            if (media != null
                                                                    && media.getSchema() != null) {
                                                                var schema = media.getSchema();
                                                                if (schema.get$ref() != null)
                                                                    schema =
                                                                            api.getComponents()
                                                                                    .getSchemas()
                                                                                    .get(
                                                                                            schema.get$ref()
                                                                                                    .substring(
                                                                                                            schema.get$ref()
                                                                                                                            .lastIndexOf(
                                                                                                                                    '/')
                                                                                                                    + 1));
                                                                if (schema != null)
                                                                    schema.setRequired(
                                                                            List.of("file"));
                                                            }
                                                        }
                                                        if (path.startsWith("/api/v1/support/")
                                                                && operation.getParameters() != null
                                                                && operation
                                                                        .getParameters()
                                                                        .stream()
                                                                        .anyMatch(
                                                                                p ->
                                                                                        p.getName()
                                                                                                .equals(
                                                                                                        "page"))) {
                                                            success.addHeaderObject(
                                                                    "X-Total-Count",
                                                                    new io.swagger.v3.oas.models
                                                                                    .headers
                                                                                    .Header()
                                                                            .description(
                                                                                    "Number of"
                                                                                        + " records"
                                                                                        + " matching"
                                                                                        + " the authorized"
                                                                                        + " scope"
                                                                                        + " and filters.")
                                                                            .schema(
                                                                                    new IntegerSchema()
                                                                                            .format(
                                                                                                    "int64")));
                                                        }
                                                    }));
        };
    }

    private static Content json(Schema<?> schema) {
        return new Content().addMediaType("application/json", new MediaType().schema(schema));
    }

    private static String parameterDescription(String name) {
        return switch (name) {
            case "page" -> "Zero-based page index. Default: 0.";
            case "size" -> "Records per page, from 1 to 100. Default: 10.";
            case "offset" -> "Zero-based number of matching records to skip.";
            case "limit" -> "Maximum number of matching records to return, from 1 to 100.";
            case "Idempotency-Key" ->
                    "Stable caller-generated key. Reuse it only for retries with the same business"
                        + " content.";
            case "X-Checkout-Secret" ->
                    "Private caller-generated checkout secret. Keep it confidential; an order UUID"
                        + " alone does not authorize access.";
            case "format" ->
                    "Fiscal artifact format: pdf, xml or cdr (ZIP). Availability depends on the"
                        + " stored fiscal evidence.";
            case "associationVersion" -> "Exact current device-association version to authorize.";
            case "function" ->
                    "Requested IAM meter function. A grant for another function does not authorize"
                        + " this operation.";
            case "status" ->
                    "Optional lifecycle-status filter using the service's documented state values.";
            case "reason" -> "Optional financial billing-reason filter.";
            case "category" -> "Optional knowledge-article category filter.";
            case "specialty" -> "Optional technician specialty filter.";
            case "available" -> "Optional technician availability filter.";
            default ->
                    name.endsWith("Id") || name.equals("id")
                            ? "Resource UUID. Possession of an identifier does not replace"
                                  + " authorization."
                            : "Operation-specific query or request parameter.";
        };
    }
}
