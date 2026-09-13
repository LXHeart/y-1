package com.grassland.intelligence.creationstudio;

import com.fasterxml.jackson.databind.JsonNode;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Set;
import java.util.UUID;

/**
 * 任务书 #101 §6.1：NEW DTO 字段校验器。
 *
 * <p>先对 JsonNode 检查真实 JSON 类型，再构造 DTO：禁止把浮点数截成版本整数、把数字转成
 * 文本、把字符串 "true" 转成布尔；未知字段、未声明 nullable 的显式 null 均为 400。
 * 不修改全局 Jackson 设置（旧客户端宽容解析不受影响）。解析错误只返回字段名和原因，
 * 不回显原值。
 */
public final class StudioRequestValidator {

    static final String CODE = "STUDIO_INVALID_INPUT";

    private StudioRequestValidator() {}

    /** 请求体必须是 JSON object（数组／标量直接 400）。 */
    public static void requireObject(JsonNode body, String what) {
        if (body == null || !body.isObject()) {
            throw invalid(what + " 必须是 JSON 对象");
        }
    }

    /** NEW DTO 拒绝未知字段；allowed 是该 DTO 的完整字段集。 */
    public static void rejectUnknownFields(JsonNode body, Set<String> allowed) {
        body.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw invalid("存在未知字段：" + field);
            }
        });
    }

    /** 必填字符串：缺失／显式 null／非字符串（数字、布尔、对象）均 400。 */
    public static String requireString(JsonNode body, String field, int maxCodePoints) {
        JsonNode node = body.path(field);
        if (node.isMissingNode() || node.isNull()) {
            throw missing(field);
        }
        if (!node.isTextual()) {
            throw type(field, "字符串");
        }
        return bounded(node.asText(), field, maxCodePoints);
    }

    /** 可省略字符串：缺失或显式 null 返回 null（nullable 语义）。 */
    public static String optionalString(JsonNode body, String field, int maxCodePoints) {
        JsonNode node = body.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw type(field, "字符串");
        }
        return bounded(node.asText(), field, maxCodePoints);
    }

    private static String bounded(String value, String field, int maxCodePoints) {
        if (maxCodePoints > 0 && value.codePointCount(0, value.length()) > maxCodePoints) {
            throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED",
                    "字段 " + field + " 超过 " + maxCodePoints + " 字符上限");
        }
        return value;
    }

    /** 必填整数：必须是 JSON 整数（浮点、字符串数字、布尔均 400），且可用 int 表示。 */
    public static int requireInt(JsonNode body, String field) {
        JsonNode node = body.path(field);
        if (node.isMissingNode() || node.isNull()) {
            throw missing(field);
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw type(field, "整数");
        }
        return node.asInt();
    }

    /** 可省略整数：缺失或 null 返回 null；存在时必须是 JSON 整数。 */
    public static Integer optionalInt(JsonNode body, String field) {
        JsonNode node = body.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw type(field, "整数");
        }
        return node.asInt();
    }

    /** 必填布尔：必须是 JSON true／false。 */
    public static boolean requireBoolean(JsonNode body, String field) {
        JsonNode node = body.path(field);
        if (node.isMissingNode() || node.isNull()) {
            throw missing(field);
        }
        if (!node.isBoolean()) {
            throw type(field, "布尔值");
        }
        return node.asBoolean();
    }

    public static Boolean optionalBoolean(JsonNode body, String field) {
        JsonNode node = body.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw type(field, "布尔值");
        }
        return node.asBoolean();
    }

    /** 必填枚举：值必须精确命中白名单。 */
    public static String requireEnum(JsonNode body, String field, Set<String> allowed) {
        String value = requireString(body, field, 64);
        if (!allowed.contains(value)) {
            throw new IntelligenceException(400, CODE, "字段 " + field + " 的值不合法");
        }
        return value;
    }

    public static String optionalEnum(JsonNode body, String field, Set<String> allowed) {
        String value = optionalString(body, field, 64);
        if (value != null && !allowed.contains(value)) {
            throw new IntelligenceException(400, CODE, "字段 " + field + " 的值不合法");
        }
        return value;
    }

    /** 必填 UUID（JSON string 形态）。 */
    public static UUID requireUuid(JsonNode body, String field) {
        String value = requireString(body, field, 64);
        return parseUuid(value, field);
    }

    public static UUID optionalUuid(JsonNode body, String field) {
        String value = optionalString(body, field, 64);
        return value == null ? null : parseUuid(value, field);
    }

    private static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw type(field, "UUID");
        }
    }

    private static IntelligenceException missing(String field) {
        return new IntelligenceException(400, CODE, "缺少必填字段：" + field);
    }

    private static IntelligenceException type(String field, String expected) {
        return new IntelligenceException(400, CODE, "字段 " + field + " 必须是" + expected);
    }

    private static IntelligenceException invalid(String message) {
        return new IntelligenceException(400, CODE, message);
    }
}
