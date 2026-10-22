package com.enrola.salesagent.lead;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Leads to play with, taken from the brief. Backs the stub lead API and the UI's "seed lead"
 * picker, so the UI never depends on the stub itself.
 */
@Component
public class SampleLeads {

	private static final List<LeadRecord> SAMPLES = List.of(
			new LeadRecord("lead-john", "John", "+61457099876", "john@gmail.com", "WA", "HBF", "$350-$450"),
			new LeadRecord("lead-jane", "Jane", "+61457099877", "jane@gmail.com", "NSW", "none", null),
			new LeadRecord("lead-lauren", "Lauren", "+61457099878", "lauren@example.com", "VIC", "Bupa", null),
			// Same person as lead-john, resubmitted with the number in local format.
			new LeadRecord("lead-john-again", "John", "0457 099 876", "john@gmail.com", "WA", "HBF", "$350-$450"));

	public List<LeadRecord> all() {
		return SAMPLES;
	}

	public Optional<LeadRecord> find(String externalId) {
		return SAMPLES.stream().filter(lead -> lead.externalId().equals(externalId)).findFirst();
	}

}
