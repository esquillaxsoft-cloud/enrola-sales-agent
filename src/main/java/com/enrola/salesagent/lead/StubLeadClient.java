package com.enrola.salesagent.lead;

import java.util.Optional;

import org.springframework.stereotype.Component;

/** Stands in for Enrola's lead API until the agent is wired to the platform. */
@Component
class StubLeadClient implements LeadClient {

	private final SampleLeads samples;

	StubLeadClient(SampleLeads samples) {
		this.samples = samples;
	}

	@Override
	public Optional<LeadRecord> fetch(String externalId) {
		return samples.find(externalId);
	}

}
