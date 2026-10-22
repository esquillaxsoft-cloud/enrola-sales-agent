package com.enrola.salesagent.lead;

import java.util.Optional;

/** Enrola's lead API: retrieve lead data by id. Stubbed for the prototype. */
public interface LeadClient {

	Optional<LeadRecord> fetch(String externalId);

}
