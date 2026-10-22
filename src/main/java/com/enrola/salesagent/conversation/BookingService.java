package com.enrola.salesagent.conversation;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enrola.salesagent.lead.Lead;
import com.enrola.salesagent.scheduling.CalendlyClient;
import com.enrola.salesagent.scheduling.CalendlyClient.BookedEvent;
import com.enrola.salesagent.scheduling.CalendlyClient.SlotUnavailableException;
import com.enrola.salesagent.scheduling.SlotFinder;

/**
 * Booking, moving and cancelling a lead's call, keeping Calendly and the conversation in step.
 * The agent's tools (commit 12) call these; the ordering rules live here, not in the prompt.
 */
@Service
public class BookingService {

	private static final Logger log = LoggerFactory.getLogger(BookingService.class);

	public sealed interface Result permits Booked, Unavailable {
	}

	public record Booked(Booking booking) implements Result {
	}

	/**
	 * The time was taken between being offered and being chosen (a lead may reply days later).
	 *
	 * @param alternatives the nearest free times, to offer instead
	 */
	public record Unavailable(Instant requested, List<Instant> alternatives) implements Result {
	}

	private final CalendlyClient calendly;
	private final SlotFinder slots;

	BookingService(CalendlyClient calendly, SlotFinder slots) {
		this.calendly = calendly;
		this.slots = slots;
	}

	@Transactional
	public Result book(Conversation conversation, Instant startTime) {
		if (conversation.getBooking() != null) {
			throw new IllegalStateException("Already booked; use reschedule");
		}
		try {
			conversation.book(toBooking(bookWithCalendly(conversation.getLead(), startTime)));
			return new Booked(conversation.getBooking());
		}
		catch (SlotUnavailableException e) {
			return unavailable(conversation, startTime);
		}
	}

	/**
	 * Books the new time <em>before</em> cancelling the old one, so a failure part-way never
	 * leaves the lead without a call. If the new time is gone, the existing booking is untouched.
	 */
	@Transactional
	public Result reschedule(Conversation conversation, Instant newStartTime) {
		Booking old = requireBooking(conversation);
		BookedEvent moved;
		try {
			moved = bookWithCalendly(conversation.getLead(), newStartTime);
		}
		catch (SlotUnavailableException e) {
			return unavailable(conversation, newStartTime);
		}
		conversation.book(toBooking(moved));
		try {
			calendly.cancel(old.getEventId());
		}
		catch (RuntimeException e) {
			// The lead has their new call; the old event is orphaned in Calendly and needs a person
			// (or a cleanup job) to cancel it. Better than the reverse.
			log.warn("Rescheduled conversation {} but couldn't cancel old event {}", conversation.getId(),
					old.getEventId(), e);
		}
		return new Booked(conversation.getBooking());
	}

	@Transactional
	public void cancel(Conversation conversation) {
		calendly.cancel(requireBooking(conversation).getEventId());
		conversation.cancelBooking();
	}

	private BookedEvent bookWithCalendly(Lead lead, Instant startTime) {
		return calendly.book(lead.getGivenName(), lead.getPhone(), lead.getEmail(), startTime);
	}

	private Unavailable unavailable(Conversation conversation, Instant requested) {
		ZoneId zone = conversation.getLead().getState().getZone();
		LocalTime at = requested.atZone(zone).toLocalTime();
		var nearest = slots.suggest(zone, requested.atZone(zone).toLocalDate(), at, at.plusMinutes(1));
		return new Unavailable(requested, nearest.slots());
	}

	private static Booking requireBooking(Conversation conversation) {
		if (conversation.getBooking() == null) {
			throw new IllegalStateException("Nothing booked");
		}
		return conversation.getBooking();
	}

	private static Booking toBooking(BookedEvent event) {
		return new Booking(event.id(), event.startTime(), event.advisorName());
	}

}
