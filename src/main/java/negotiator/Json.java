package negotiator;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** JSON helpers backed by Jackson. Two mappers: one pretty, one canonical. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(SerializationFeature.INDENT_OUTPUT, false);
    public static final ObjectMapper PRETTY = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(SerializationFeature.INDENT_OUTPUT, true);
    /** Deterministic ordering: used for hashes and equality checks. */
    public static final ObjectMapper CANON = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Json() {}

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] bytes(Object value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    public static String writeCanonical(Object value) {
        try {
            return CANON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode read(String body) {
        try {
            return MAPPER.readTree(body == null || body.isEmpty() ? "{}" : body);
        } catch (IOException e) {
            throw new BadRequest("非法 JSON: " + e.getMessage(), e);
        }
    }

    public static JsonNode parse(byte[] body) {
        try {
            return MAPPER.readTree(body == null || body.length == 0 ? new byte[]{'{','}'} : body);
        } catch (IOException e) {
            throw new BadRequest("非法 JSON: " + e.getMessage(), e);
        }
    }

    public static String sha256(Object value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(writeCanonical(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String shortId(String prefix, Object seed) {
        return prefix + "_" + sha256(seed).substring(0, 10);
    }

    public static JsonNodeFactory nodeFactory() {
        return JsonNodeFactory.instance;
    }

    public static class BadRequest extends RuntimeException {
        public BadRequest(String message) { super(message); }
        public BadRequest(String message, Throwable cause) { super(message, cause); }
    }
}
