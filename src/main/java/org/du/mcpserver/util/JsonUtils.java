package org.du.mcpserver.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class JsonUtils {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson GSON_COMPACT = new Gson();

    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    public static String toJsonCompact(Object obj) {
        return GSON_COMPACT.toJson(obj);
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        return GSON.fromJson(json, clazz);
    }

    public static JsonObject createErrorResponse(int errorCode, String message, long id) {
        JsonObject error = new JsonObject();
        error.addProperty("code", errorCode);
        error.addProperty("message", message);

        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("error", error);
        response.addProperty("id", id);

        return response;
    }

    public static JsonObject createErrorResponse(int errorCode, String message) {
        return createErrorResponse(errorCode, message, 1);
    }

    public static JsonObject createSuccessResponse(JsonElement result, long id) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("result", result);
        response.addProperty("id", id);

        return response;
    }

    public static JsonObject createSuccessResponse(JsonElement result) {
        return createSuccessResponse(result, 1);
    }

    public static JsonObject createSuccessResponse(String result, long id) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.addProperty("result", result);
        response.addProperty("id", id);

        return response;
    }

    public static JsonObject createSuccessResponse(String result) {
        return createSuccessResponse(result, 1);
    }

    public static JsonObject createSuccessResponse(boolean result, long id) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.addProperty("result", result);
        response.addProperty("id", id);

        return response;
    }

    public static JsonObject createSuccessResponse(boolean result) {
        return createSuccessResponse(result, 1);
    }
}
