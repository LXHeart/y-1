package com.grassland.identity.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.identity.organization.PermissionTier;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 材料策略：各 tier(+受监管行业) 的必填集合 + 缺料校验。 */
class PermissionMaterialPolicyTest {

    @Test
    void basicPublishRequiresLicenseAndContact() {
        assertThat(PermissionMaterialPolicy.requiredMaterialTypes(PermissionTier.BASIC_PUBLISH, Industry.RETAIL))
                .containsExactlyInAnyOrder(MaterialType.BUSINESS_LICENSE, MaterialType.CONTACT_INFO);
    }

    @Test
    void financeTransactionRequiresFullSet() {
        assertThat(PermissionMaterialPolicy.requiredMaterialTypes(PermissionTier.FINANCE_TRANSACTION, Industry.RETAIL))
                .containsExactlyInAnyOrder(MaterialType.BUSINESS_LICENSE, MaterialType.LEGAL_REPRESENTATIVE,
                        MaterialType.FINANCIAL_QUALIFICATION, MaterialType.CONTACT_INFO);
    }

    @Test
    void regulatedIndustryAddsIndustryLicense() {
        assertThat(PermissionMaterialPolicy.requiredMaterialTypes(PermissionTier.BASIC_PUBLISH, Industry.EDUCATION))
                .contains(MaterialType.INDUSTRY_LICENSE);
        assertThat(PermissionMaterialPolicy.requiredMaterialTypes(PermissionTier.BASIC_PUBLISH, Industry.BEAUTY))
                .contains(MaterialType.INDUSTRY_LICENSE);
    }

    @Test
    void draftHasNoRequiredMaterials() {
        assertThat(PermissionMaterialPolicy.requiredMaterialTypes(PermissionTier.DRAFT, Industry.OTHER)).isEmpty();
    }

    @Test
    void validateCompleteMaterialsPasses() {
        PermissionMaterialPolicy.validate(PermissionTier.FINANCE_TRANSACTION, Industry.RETAIL, Map.of(
                "business_license", "BL", "legal_representative", "LR",
                "financial_qualification", "FQ", "contact_info", "c"));
    }

    @Test
    void validateMissingRequiredThrowsListingType() {
        assertThatThrownBy(() -> PermissionMaterialPolicy.validate(PermissionTier.FINANCE_TRANSACTION, Industry.RETAIL,
                Map.of("business_license", "BL", "contact_info", "c")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("legal_representative")
                .hasMessageContaining("financial_qualification");
    }

    @Test
    void validateBlankValueCountsAsMissing() {
        assertThatThrownBy(() -> PermissionMaterialPolicy.validate(PermissionTier.BASIC_PUBLISH, Industry.RETAIL,
                Map.of("business_license", "BL", "contact_info", " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contact_info");
    }

    @Test
    void validateRegulatedIndustryMissingLicenseThrows() {
        assertThatThrownBy(() -> PermissionMaterialPolicy.validate(PermissionTier.BASIC_PUBLISH, Industry.EDUCATION,
                Map.of("business_license", "BL", "contact_info", "c")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("industry_license");
    }
}
