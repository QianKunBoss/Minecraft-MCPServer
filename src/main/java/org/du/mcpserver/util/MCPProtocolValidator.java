package org.du.mcpserver.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

public class MCPProtocolValidator {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final List<String> VALID_METHODS = List.of(
            "initialize",
            "tools/list",
            "tools/call",
            "notifications/initialized",
            "notifications/cancelled",
            "notifications/progress"
    );

    public static class ValidationResult {
        public final boolean valid;
        public final List<String> errors;

        public ValidationResult(boolean valid, List<String> errors) {
            this.valid = valid;
            this.errors = errors;
        }

        public static ValidationResult success() {
            return new ValidationResult(true, new ArrayList<>());
        }

        public static ValidationResult failure(String error) {
            List<String> errors = new ArrayList<>();
            errors.add(error);
            return new ValidationResult(false, errors);
        }
    }

    public static ValidationResult validateRequest(String requestBody) {
        List<String> errors = new ArrayList<>();

        try {
            JsonElement element = JsonParser.parseString(requestBody);
            if (!element.isJsonObject()) {
                return ValidationResult.failure("Request body must be a JSON object");
            }

            JsonObject request = element.getAsJsonObject();

            if (!request.has("jsonrpc")) {
                errors.add("Missing required field: jsonrpc");
            } else {
                String jsonrpc = request.get("jsonrpc").getAsString();
                if (!"2.0".equals(jsonrpc)) {
                    errors.add("Invalid jsonrpc version: " + jsonrpc + ", expected: 2.0");
                }
            }

            if (!request.has("method")) {
                errors.add("Missing required field: method");
            } else {
                if (!request.get("method").isJsonPrimitive() || !request.get("method").getAsJsonPrimitive().isString()) {
                    errors.add("Field 'method' must be a string");
                } else {
                    String method = request.get("method").getAsString();
                    if (!VALID_METHODS.contains(method)) {
                        errors.add("Unknown method: " + method);
                    }
                }
            }

            if (request.has("id")) {
                JsonElement id = request.get("id");
                if (!id.isJsonPrimitive() || (!id.getAsJsonPrimitive().isNumber() && !id.getAsJsonPrimitive().isString())) {
                    errors.add("Field 'id' must be a string or number");
                }
            }

            if (request.has("params")) {
                JsonElement params = request.get("params");
                if (!params.isJsonObject() && !params.isJsonArray()) {
                    errors.add("Field 'params' must be an object or array");
                }
            }

            if (request.has("method") && request.get("method").isJsonPrimitive()
                    && request.get("method").getAsString().equals("initialize")) {
                validateInitializeParams(request, errors);
            }

        } catch (Exception e) {
            return ValidationResult.failure("Invalid JSON: " + e.getMessage());
        }

        return errors.isEmpty()
                ? ValidationResult.success()
                : new ValidationResult(false, errors);
    }

    private static void validateInitializeParams(JsonObject request, List<String> errors) {
        if (!request.has("params")) {
            errors.add("initialize requires params");
            return;
        }

        JsonElement paramsEl = request.get("params");
        if (!paramsEl.isJsonObject()) {
            errors.add("initialize params must be an object");
            return;
        }

        JsonObject params = paramsEl.getAsJsonObject();

        if (!params.has("protocolVersion")) {
            errors.add("initialize params missing required field: protocolVersion");
        } else {
            String version = params.get("protocolVersion").getAsString();
            if (!isValidVersionFormat(version)) {
                errors.add("Invalid protocolVersion format: " + version);
            }
        }

        if (params.has("capabilities")) {
            if (!params.get("capabilities").isJsonObject()) {
                errors.add("capabilities must be an object");
            }
        }

        if (params.has("clientInfo")) {
            if (!params.get("clientInfo").isJsonObject()) {
                errors.add("clientInfo must be an object");
            }
        }
    }

    public static ValidationResult validateResponse(JsonObject response) {
        List<String> errors = new ArrayList<>();

        try {
            if (!response.has("jsonrpc")) {
                errors.add("Missing required field: jsonrpc");
            } else {
                String jsonrpc = response.get("jsonrpc").getAsString();
                if (!"2.0".equals(jsonrpc)) {
                    errors.add("Invalid jsonrpc version: " + jsonrpc + ", expected: 2.0");
                }
            }

            if (!response.has("id")) {
                errors.add("Missing required field: id");
            } else {
                JsonElement id = response.get("id");
                if (!id.isJsonPrimitive() || (!id.getAsJsonPrimitive().isNumber() && !id.getAsJsonPrimitive().isString())) {
                    errors.add("Field 'id' must be a string or number");
                }
            }

            boolean hasResult = response.has("result");
            boolean hasError = response.has("error");

            if (hasResult && hasError) {
                errors.add("Response cannot have both result and error");
            }

            if (!hasResult && !hasError) {
                errors.add("Response must have either result or error");
            }

            if (hasError) {
                JsonElement error = response.get("error");
                if (!error.isJsonObject()) {
                    errors.add("error must be an object");
                } else {
                    JsonObject errorObj = error.getAsJsonObject();
                    if (!errorObj.has("code")) {
                        errors.add("error missing required field: code");
                    } else if (!errorObj.get("code").isJsonPrimitive() || !errorObj.get("code").getAsJsonPrimitive().isNumber()) {
                        errors.add("error.code must be a number");
                    }
                    if (!errorObj.has("message")) {
                        errors.add("error missing required field: message");
                    } else if (!errorObj.get("message").isJsonPrimitive() || !errorObj.get("message").getAsJsonPrimitive().isString()) {
                        errors.add("error.message must be a string");
                    }
                }
            }

        } catch (Exception e) {
            return ValidationResult.failure("Invalid response: " + e.getMessage());
        }

        return errors.isEmpty()
                ? ValidationResult.success()
                : new ValidationResult(false, errors);
    }

    public static String getProtocolVersion() {
        return PROTOCOL_VERSION;
    }

    private static boolean isValidVersionFormat(String version) {
        return version.matches("\\d{4}-\\d{2}-\\d{2}");
    }
}
