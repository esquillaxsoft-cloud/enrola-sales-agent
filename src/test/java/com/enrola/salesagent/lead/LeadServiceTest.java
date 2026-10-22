package com.enrola.salesagent.lead;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** The mapping from the lead API's raw fields to our snapshot; no Spring needed. */
class LeadServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");

	@Test
	void mapsTheBriefsExampleLead() {
		Lead john = LeadService.toLead(
				new LeadRecord("lead-john", "John", "+61457099876", "john@gmail.com", "WA", "HBF", "$350-$450"), NOW);

		assertThat(john.getPhone()).isEqualTo("+61457099876");
		assertThat(john.getState()).isEqualTo(AuState.WA);
		assertThat(john.getCurrentProvider()).isEqualTo("HBF");
		assertThat(john.getCurrentPremium()).isEqualTo("$350-$450");
	}

	@Test
	void providerNoneMeansNoInsurer() {
		Lead jane = LeadService.toLead(
				new LeadRecord("lead-jane", "Jane", "0457 099 877", "jane@gmail.com", "nsw", "none", ""), NOW);

		assertThat(jane.getCurrentProvider()).isNull();
		assertThat(jane.getCurrentPremium()).isNull();
		assertThat(jane.getState()).isEqualTo(AuState.NSW);
		assertThat(jane.getPhone()).isEqualTo("+61457099877");
	}

}
