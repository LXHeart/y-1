package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.commerce.CommerceRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Component;

/** Batch enrichment of task read models, with no write-side transitions. */
@Component
public class TaskReadEnrichment {
	private final CommerceRepository commercePackages;
	private final com.grassland.marketplace.reputation.MerchantCreditService merchantCredits;

	public TaskReadEnrichment(CommerceRepository commercePackages,
			com.grassland.marketplace.reputation.MerchantCreditService merchantCredits) {
		this.commercePackages = commercePackages;
		this.merchantCredits = merchantCredits;
	}

	Mono<List<Map<String, Object>>> withCommerceSummaries(List<Map<String, Object>> bodies) {
		List<String> packageIds = bodies.stream().map(body -> (String) body.get("commercePackageId"))
				.filter(java.util.Objects::nonNull).distinct().toList();
		if (packageIds.isEmpty()) {
			return Mono.just(bodies);
		}
		return commercePackages.findPromotionSummaries(packageIds).map(summaries -> {
			for (Map<String, Object> body : bodies) {
				String packageId = (String) body.get("commercePackageId");
				CommerceRepository.PromotionSummary summary = packageId == null ? null : summaries.get(packageId);
				if (summary != null) {
					Map<String, Object> block = new LinkedHashMap<>();
					block.put("id", summary.packageId());
					block.put("title", summary.title());
					block.put("priceCents", summary.priceCents());
					block.put("recommenderShareBps", summary.recommenderShareBps());
					if (summary.recommenderFixedCents() != null) {
						block.put("recommenderFixedCents", summary.recommenderFixedCents());
					}
					block.put("status", summary.packageStatus());
					body.put("commercePackage", block);
				}
			}
			return bodies;
		});
	}

	Mono<Map<String, com.grassland.marketplace.reputation.MerchantCreditService.MerchantCredit>> withMerchantCredits(
			List<Map<String, Object>> bodies) {
		List<String> orgIds = bodies.stream().map(body -> (String) body.get("organizationId"))
				.filter(java.util.Objects::nonNull).distinct().toList();
		if (orgIds.isEmpty()) {
			return Mono.just(Map.of());
		}
		return reactor.core.publisher.Flux.fromIterable(orgIds).flatMap(merchantCredits::compute)
				.collectMap(com.grassland.marketplace.reputation.MerchantCreditService.MerchantCredit::organizationId)
				.map(credits -> {
					for (Map<String, Object> body : bodies) {
						var credit = credits.get((String) body.get("organizationId"));
						if (credit != null) {
							body.put("merchantCredit", merchantCredits.summaryBody(credit));
						}
					}
					return credits;
				});
	}

}
