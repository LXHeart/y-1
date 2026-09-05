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
		// 任务书 #78 卡 G：contact_info 只收 11 位手机号（材料齐 + 手机号合法才通过）
		PermissionMaterialPolicy.validate(PermissionTier.FINANCE_TRANSACTION, Industry.RETAIL,
				Map.of("business_license", "BL", "legal_representative", "LR", "financial_qualification", "FQ",
						"contact_info", "13800138000"));
	}

	@Test
	void validateRejectsNonMobileContactInfo() {
		assertThatThrownBy(() -> PermissionMaterialPolicy.validate(PermissionTier.BASIC_PUBLISH, Industry.RETAIL,
				Map.of("business_license", "BL", "contact_info", "010-12345678")))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("手机号");
	}

	@Test
	void validateRejectsOverlongMaterialValue() {
		assertThatThrownBy(() -> PermissionMaterialPolicy.validate(PermissionTier.BASIC_PUBLISH, Industry.RETAIL,
				Map.of("business_license", "B".repeat(129), "contact_info", "13800138000")))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("过长");
	}

	@Test
	void validateMissingRequiredThrowsListingType() {
		assertThatThrownBy(() -> PermissionMaterialPolicy.validate(PermissionTier.FINANCE_TRANSACTION, Industry.RETAIL,
				Map.of("business_license", "BL", "contact_info", "c"))).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("legal_representative").hasMessageContaining("financial_qualification");
	}

	@Test
	void validateBlankValueCountsAsMissing() {
		assertThatThrownBy(() -> PermissionMaterialPolicy.validate(PermissionTier.BASIC_PUBLISH, Industry.RETAIL,
				Map.of("business_license", "BL", "contact_info", " "))).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("contact_info");
	}

	@Test
	void validateRegulatedIndustryMissingLicenseThrows() {
		assertThatThrownBy(() -> PermissionMaterialPolicy.validate(PermissionTier.BASIC_PUBLISH, Industry.EDUCATION,
				Map.of("business_license", "BL", "contact_info", "c"))).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("industry_license");
	}
}
