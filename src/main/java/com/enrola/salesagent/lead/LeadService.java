package com.enrola.salesagent.lead;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Brings a lead in from Enrola's lead API and stores a normalised snapshot. */
@Service
public class LeadService {

	private final LeadClient leadClient;
	private final LeadRepository leads;
	private final Clock clock;

	LeadService(LeadClient leadClient, LeadRepository leads, Clock clock) {
		this.leadClient = leadClient;
		this.leads = leads;
		this.clock = clock;
	}

	/** Idempotent: ingesting the same lead id twice returns the stored lead. */
	@Transactional
	public Lead ingest(String externalId) {
		return leads.findByExternalId(externalId).orElseGet(() -> {
			LeadRecord record = leadClient.fetch(externalId)
					.orElseThrow(() -> new NoSuchElementException("Lead API has no lead " + externalId));
			return leads.save(toLead(record, clock.instant()));
		});
	}

	static Lead toLead(LeadRecord record, Instant now) {
		return new Lead(
				record.externalId(),
				record.givenName().trim(),
				PhoneNumbers.toE164(record.phone()),
				blankToNull(record.email()),
				AuState.valueOf(record.state().trim().toUpperCase(Locale.ROOT)),
				providerOrNull(record.currentProvider()),
				blankToNull(record.currentPremium()),
				now);
	}

	/** Comparato sends "none" when the lead has no insurer. */
	private static String providerOrNull(String provider) {
		String value = blankToNull(provider);
		return value == null || value.equalsIgnoreCase("none") ? null : value;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

}
