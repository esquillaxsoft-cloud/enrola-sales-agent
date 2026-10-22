package com.enrola.salesagent.lead;

/**
 * A lead as Enrola's lead API returns it: raw strings, before any normalisation. Comparato's
 * custom fields arrive as-is, e.g. currentProvider "none" or currentPremium "$350-$450".
 */
public record LeadRecord(
		String externalId,
		String givenName,
		String phone,
		String email,
		String state,
		String currentProvider,
		String currentPremium) {
}
