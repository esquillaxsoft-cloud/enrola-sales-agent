package com.enrola.salesagent.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Comparato's Calendly: one round-robin event type pooling every advisor (DESIGN.md §3). Calendly
 * picks the advisor when a call is booked. Stubbed for the prototype.
 */
public interface CalendlyClient {

	/** Calendly limits an availability query to 7 days. */
	Duration MAX_RANGE = Duration.ofDays(7);

	/**
	 * Start times of free 15-minute calls in [start, end).
	 *
	 * @throws IllegalArgumentException if the range is longer than {@link #MAX_RANGE}
	 */
	List<Instant> availableStartTimes(Instant start, Instant end);

	/** @throws SlotUnavailableException if the time is no longer free */
	BookedEvent book(String name, String phone, String email, Instant startTime);

	void cancel(String eventId);

	/** @param advisorName the advisor Calendly assigned; it's on the invite */
	record BookedEvent(String id, Instant startTime, String advisorName) {
	}

	class SlotUnavailableException extends RuntimeException {

		public SlotUnavailableException(Instant startTime) {
			super("No longer available: " + startTime);
		}

	}

}
