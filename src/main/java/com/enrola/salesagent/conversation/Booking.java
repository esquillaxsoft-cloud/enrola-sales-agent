package com.enrola.salesagent.conversation;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * The lead's current Calendly booking. Kept on the conversation so a reschedule request days
 * later knows which event to move.
 */
@Embeddable
public class Booking {

	@Column(name = "booking_event_id")
	private String eventId;

	@Column(name = "booking_start_time")
	private Instant startTime;

	@Column(name = "booking_advisor_name")
	private String advisorName;

	protected Booking() {
		// for JPA
	}

	public Booking(String eventId, Instant startTime, String advisorName) {
		this.eventId = eventId;
		this.startTime = startTime;
		this.advisorName = advisorName;
	}

	public String getEventId() {
		return eventId;
	}

	public Instant getStartTime() {
		return startTime;
	}

	public String getAdvisorName() {
		return advisorName;
	}

}
