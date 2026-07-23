package com.grassland.identity.permission;

import com.grassland.identity.organization.PermissionTier;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 商家权限升级的材料策略（HLD D-05「材料 schema」）。纯逻辑，按 tier(+受监管行业) 决定必填材料类型。
 *
 * <ul>
 *   <li>{@link PermissionTier#BASIC_PUBLISH}：business_license + contact_info。</li>
 *   <li>{@link PermissionTier#FINANCE_TRANSACTION}：business_license + legal_representative
 *       + financial_qualification + contact_info。</li>
 *   <li>受监管行业（{@link Industry#requiresIndustryLicense()}）追加 industry_license。</li>
 * </ul>
 *
 * <p>{@link #validate} 校验 materials（{@code Map<dbValue, 文本>}）含全部必填且非空，缺失抛
 * {@link IllegalArgumentException}（消息列出缺失类型）。
 */
public final class PermissionMaterialPolicy {

    private PermissionMaterialPolicy() {}

    /** 该 tier(+行业) 必填的材料类型集合（顺序稳定便于错误消息与测试）。 */
    public static Set<MaterialType> requiredMaterialTypes(PermissionTier tier, Industry industry) {
        Set<MaterialType> required = new LinkedHashSet<>();
        if (tier == PermissionTier.BASIC_PUBLISH) {
            required.add(MaterialType.BUSINESS_LICENSE);
            required.add(MaterialType.CONTACT_INFO);
        } else if (tier == PermissionTier.FINANCE_TRANSACTION) {
            required.add(MaterialType.BUSINESS_LICENSE);
            required.add(MaterialType.LEGAL_REPRESENTATIVE);
            required.add(MaterialType.FINANCIAL_QUALIFICATION);
            required.add(MaterialType.CONTACT_INFO);
        }
        // DRAFT 无必填（不可申请）；受监管行业追加行业许可证。
        if (industry != null && industry.requiresIndustryLicense()) {
            required.add(MaterialType.INDUSTRY_LICENSE);
        }
        return required;
    }

    /** 校验 materials 覆盖全部必填且值非空；缺失/空值抛 {@link IllegalArgumentException}。 */
    public static void validate(PermissionTier tier, Industry industry, Map<String, String> materials) {
        Set<MaterialType> required = requiredMaterialTypes(tier, industry);
        Set<String> missing = new LinkedHashSet<>();
        for (MaterialType type : required) {
            String value = materials == null ? null : materials.get(type.dbValue());
            if (value == null || value.isBlank()) {
                missing.add(type.dbValue());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("missing required materials: " + String.join(", ", missing));
        }
    }
}
