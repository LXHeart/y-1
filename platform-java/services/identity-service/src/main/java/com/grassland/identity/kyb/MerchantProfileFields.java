package com.grassland.identity.kyb;

import com.grassland.identity.auth.IdentityException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Pattern;

/** 商家认证联系方式与居民身份证号的服务端归一化、格式校验。 */
public final class MerchantProfileFields {

    private static final Pattern MOBILE_PHONE = Pattern.compile("1[3-9][0-9]{9}");
    private static final Pattern LANDLINE_PHONE = Pattern.compile("0[0-9]{2,3}(?:[-\\s]?[0-9]{7,8})");
    private static final Pattern SERVICE_PHONE = Pattern.compile("(?:400|800)[-\\s]?[0-9]{3}[-\\s]?[0-9]{4}");
    private static final Pattern EMAIL_LOCAL = Pattern.compile("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+");
    private static final Pattern EMAIL_DOMAIN_LABEL =
            Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
    private static final Pattern LEGACY_ID = Pattern.compile("[0-9]{15}");
    private static final Pattern CURRENT_ID = Pattern.compile("[0-9]{17}[0-9X]");
    private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] ID_CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    private MerchantProfileFields() {
    }

    /** 可选联系电话：支持大陆手机号、座机及 400/800 服务号码；blank 表示清空。 */
    public static String contactPhone(String value) {
        String normalized = optional(value);
        if (normalized == null) {
            return null;
        }
        if (!MOBILE_PHONE.matcher(normalized).matches()
                && !LANDLINE_PHONE.matcher(normalized).matches()
                && !SERVICE_PHONE.matcher(normalized).matches()) {
            throw new IdentityException(400, "联系电话格式无效，请输入 11 位手机号、座机或 400/800 服务号码");
        }
        return normalized;
    }

    /** 可选联系邮箱：按常规互联网邮箱的总长、local-part 和 DNS 域名标签校验；blank 表示清空。 */
    public static String contactEmail(String value) {
        String normalized = optional(value);
        if (normalized == null) {
            return null;
        }
        int at = normalized.lastIndexOf('@');
        if (normalized.length() > 254 || at <= 0 || at == normalized.length() - 1) {
            throw invalidEmail();
        }

        String local = normalized.substring(0, at);
        String domain = normalized.substring(at + 1);
        if (local.length() > 64 || local.startsWith(".") || local.endsWith(".") || local.contains("..")
                || !EMAIL_LOCAL.matcher(local).matches()) {
            throw invalidEmail();
        }

        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2 || labels[labels.length - 1].length() < 2) {
            throw invalidEmail();
        }
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63 || !EMAIL_DOMAIN_LABEL.matcher(label).matches()) {
                throw invalidEmail();
            }
        }
        return normalized;
    }

    /**
     * 可选中国居民身份证号：校验 15/18 位格式、出生日期、顺序码及 18 位 GB 11643 校验码。
     *
     * <p>地址码使用 2023-06-30 的 2,978 个现行县级统计区划码，并兼容 70 个明确命名的常见已撤销码；
     * 该兼容集合不宣称覆盖完整历年 GB/T 2260。
     */
    public static String legalPersonIdNumber(String value) {
        String normalized = optional(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!isValidIdNumber(normalized)) {
            throw new IdentityException(400, "法人身份证号格式无效，请输入有效的 15 位或 18 位身份证号");
        }
        return normalized;
    }

    private static boolean isValidIdNumber(String value) {
        if (LEGACY_ID.matcher(value).matches()) {
            int year = 1900 + Integer.parseInt(value.substring(6, 8));
            return hasValidAreaCode(value.substring(0, 6))
                    && hasValidBirthDate(year, value.substring(8, 10), value.substring(10, 12))
                    && !value.substring(12, 15).equals("000");
        }
        if (!CURRENT_ID.matcher(value).matches() || !hasValidAreaCode(value.substring(0, 6))) {
            return false;
        }
        int year = Integer.parseInt(value.substring(6, 10));
        if (!hasValidBirthDate(year, value.substring(10, 12), value.substring(12, 14))
                || value.substring(14, 17).equals("000")) {
            return false;
        }

        int weightedSum = 0;
        for (int index = 0; index < ID_WEIGHTS.length; index++) {
            weightedSum += (value.charAt(index) - '0') * ID_WEIGHTS[index];
        }
        return value.charAt(17) == ID_CHECK_CODES[weightedSum % 11];
    }

    private static boolean hasValidAreaCode(String value) {
        return ChineseIdAreaCodes.contains(value);
    }

    private static boolean hasValidBirthDate(int year, String month, String day) {
        if (year < 1800 || year > 2099) {
            return false;
        }
        try {
            LocalDate birthDate = LocalDate.of(year, Integer.parseInt(month), Integer.parseInt(day));
            return !birthDate.isAfter(LocalDate.now());
        } catch (DateTimeException | NumberFormatException error) {
            return false;
        }
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static IdentityException invalidEmail() {
        return new IdentityException(400, "联系邮箱格式无效");
    }
}
