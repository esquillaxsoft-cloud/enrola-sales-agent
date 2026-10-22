package com.enrola.salesagent.scheduling;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Picks up to three call times to offer a lead, as in the designer's step 4: times that suit the
 * day and time the lead asked for, or else the closest free times. ("I can't see a mid-morning
 * spot on Monday, but the closest I've got is Monday 12:00 PM, or Tuesday at 9:00 or 9:30 AM.")
 *
 * <p>The lead's day and window are in the lead's local time; Calendly works in instants.
 */
@Component
public class SlotFinder {

	static final int MAX_OFFERED = 3;

	/**
	 * @param slots             up to three start times, earliest first: those in the window the
	 *                          lead asked for, topped up with the nearest ones outside it
	 * @param matchesPreference true when at least one slot is in the window; false when they're
	 *                          all alternatives
	 */
	public record Suggestion(List<Instant> slots, boolean matchesPreference) {
	}

	private final CalendlyClient calendly;
	private final Clock clock;

	SlotFinder(CalendlyClient calendly, Clock clock) {
		this.calendly = calendly;
		this.clock = clock;
	}

	/**
	 * @param zone the lead's time zone
	 * @param day  the day the lead asked about, in their time
	 * @param from start of the window they asked for ("mid-morning" might be 9:30), inclusive
	 * @param to   end of the window, exclusive; use 00:00..23:59 for "any time that day"
	 */
	public Suggestion suggest(ZoneId zone, LocalDate day, LocalTime from, LocalTime to) {
		Instant windowStart = day.atTime(from).atZone(zone).toInstant();
		Instant windowEnd = day.atTime(to).atZone(zone).toInstant();

		Instant now = clock.instant();
		Instant searchStart = later(day.atStartOfDay(zone).toInstant(), now);
		List<Instant> free = calendly.availableStartTimes(searchStart, searchStart.plus(CalendlyClient.MAX_RANGE));

		// Closest to the window first (inside it counts as zero, earliest first), so times that fit
		// come first and any spare places go to the nearest times that don't.
		List<Instant> offered = free.stream()
				.sorted(Comparator.comparing((Instant slot) -> distance(slot, windowStart, windowEnd))
						.thenComparing(Comparator.naturalOrder()))
				.limit(MAX_OFFERED)
				.sorted()
				.toList();
		boolean anyFit = offered.stream().anyMatch(slot -> distance(slot, windowStart, windowEnd).isZero());
		return new Suggestion(offered, anyFit);
	}

	/** How far a slot is from the window: zero inside it, otherwise to the nearer edge. */
	static Duration distance(Instant slot, Instant windowStart, Instant windowEnd) {
		if (slot.isBefore(windowStart)) {
			return Duration.between(slot, windowStart);
		}
		if (!slot.isBefore(windowEnd)) {
			return Duration.between(windowEnd, slot);
		}
		return Duration.ZERO;
	}

	private static Instant later(Instant a, Instant b) {
		return a.isAfter(b) ? a : b;
	}

}
