package com.grassland.intelligence.creationstudio;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 任务书 #101 §6.1：NEW DTO 字段校验器。
 *
 * <p>Spring Boot 4 的 HTTP codec 走 Jackson 3（{@code tools.jackson}），控制器以
 * {@code Map<String, Object>} 接请求（house style，见 VideoRecreationController）；本工具在
 * Map 上检查<b>真实 JSON 类型</b>再构造 DTO：禁止把浮点数截成版本整数、把数字转成文本、把字符串
 * "true" 转成布尔；未知字段、未声明 nullable 的显式 null 均为 400。解析错误只返回字段名和原因，
 * 不回显原值。
 */
public final class StudioRequestValidator {

    static final String CODE = "STUDIO_INVALID_INPUT";

    private StudioRequestValidator() {}

    /** 请求体必须是 JSON object（由 Map 承载；null 直接 400）。 */
    public static Map<String, Object> requireObject(Map<String, Object> body, String what) {
        if (body == null) {
            throw invalid(what + " 不能为空");
        }
        return body;
    }

    /** NEW DTO 拒绝未知字段；allowed 是该 DTO 的完整字段集。 */
    public static void rejectUnknownFields(Map<String, Object> body, Set<String> allowed) {
        for (String field : body.keySet()) {
            if (!allowed.contains(field)) {
                throw invalid("存在未知字段：" + field);
            }
        }
    }

    /** 必填字符串：缺失／显式 null／非字符串（数字、布尔、对象）均 400。 */
    public static String requireString(Map<String, Object> body, String field, int maxCodePoints) {
        Object value = body.get(field);
        if (value == null) {
            throw missing(field);
        }
        if (!(value instanceof String text)) {
            throw type(field, "字符串");
        }
        return bounded(text, field, maxCodePoints);
    }

    /** 可省略字符串：缺失或显式 null 返回 null（nullable 语义）。 */
    public static String optionalString(Map<String, Object> body, String field, int maxCodePoints) {
        Object value = body.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw type(field, "字符串");
        }
        return bounded(text, field, maxCodePoints);
    }

    private static String bounded(String value, String field, int maxCodePoints) {
        if (maxCodePoints > 0 && value.codePointCount(0, value.length()) > maxCodePoints) {
            throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED",
                    "字段 " + field + " 超过 " + maxCodePoints + " 字符上限");
        }
        return value;
    }

    /** 必填整数：必须是 JSON 整数（浮点、字符串数字、布尔均 400）且可用 int 表示。 */
    public static int requireInt(Map<String, Object> body, String field) {
        Integer value = integerValue(body.get(field), field);
        if (value == null) {
            throw missing(field);
        }
        return value;
    }

    /** 可省略整数：缺失或 null 返回 null；存在时必须是 JSON 整数。 */
    public static Integer optionalInt(Map<String, Object> body, String field) {
        return integerValue(body.get(field), field);
    }

    private static Integer integerValue(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer number) {
            return number;
        }
        if (value instanceof Long number) {
            if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                throw type(field, "32 位整数");
            }
            return number.intValue();
        }
        // Double/Float/BigDecimal/String/Boolean 一律拒绝——浮点不能截成整数，字符串不能转数字。
        throw type(field, "整数");
    }

    /** 必填布尔：必须是 JSON true／false。 */
    public static boolean requireBoolean(Map<String, Object> body, String field) {
        Boolean value = booleanValue(body.get(field), field);
        if (value == null) {
            throw missing(field);
        }
        return value;
    }

    public static Boolean optionalBoolean(Map<String, Object> body, String field) {
        return booleanValue(body.get(field), field);
    }

    private static Boolean booleanValue(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw type(field, "布尔值");
    }

    /** 必填枚举：值必须精确命中白名单。 */
    public static String requireEnum(Map<String, Object> body, String field, Set<String> allowed) {
        String value = requireString(body, field, 64);
        if (!allowed.contains(value)) {
            throw new IntelligenceException(400, CODE, "字段 " + field + " 的值不合法");
        }
        return value;
    }

    public static String optionalEnum(Map<String, Object> body, String field, Set<String> allowed) {
        String value = optionalString(body, field, 64);
        if (value != null && !allowed.contains(value)) {
            throw new IntelligenceException(400, CODE, "字段 " + field + " 的值不合法");
        }
        return value;
    }

    /** 必填 UUID（JSON string 形态）。 */
    public static UUID requireUuid(Map<String, Object> body, String field) {
        String value = requireString(body, field, 64);
        return parseUuid(value, field);
    }

    public static UUID optionalUuid(Map<String, Object> body, String field) {
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

    /** 数组字段：缺失或 null 返回 null；存在时必须是 JSON 数组。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> optionalObjectArray(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw type(field, "数组");
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?>)) {
                throw new IntelligenceException(400, CODE, "字段 " + field + " 的元素必须是对象");
            }
        }
        return (List<Map<String, Object>>) (List<?>) list;
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
