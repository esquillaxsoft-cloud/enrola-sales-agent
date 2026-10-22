package com.enrola.salesagent.lead;

import java.time.ZoneId;

/**
 * Australian states and territories, as Comparato sends them on a lead. The state is the only
 * time-zone signal we have, so it decides the lead's local time.
 */
public enum AuState {

	ACT("Australia/Sydney"),
	NSW("Australia/Sydney"),
	NT("Australia/Darwin"),
	QLD("Australia/Brisbane"),
	SA("Australia/Adelaide"),
	TAS("Australia/Hobart"),
	VIC("Australia/Melbourne"),
	WA("Australia/Perth");

	private final ZoneId zone;

	AuState(String zone) {
		this.zone = ZoneId.of(zone);
	}

	/** IANA zone, so daylight saving (or none, for QLD, NT and WA) is handled for us. */
	public ZoneId getZone() {
		return zone;
	}

}
