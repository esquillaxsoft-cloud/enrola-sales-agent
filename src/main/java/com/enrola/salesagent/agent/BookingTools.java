package com.enrola.salesagent.agent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.enrola.salesagent.conversation.Booking;
import com.enrola.salesagent.conversation.BookingService;
import com.enrola.salesagent.conversation.BookingService.Booked;
import com.enrola.salesagent.conversation.BookingService.Result;
import com.enrola.salesagent.conversation.BookingService.Unavailable;
import com.enrola.salesagent.conversation.Conversation;
import com.enrola.salesagent.conversation.ConversationRepository;
import com.enrola.salesagent.scheduling.SlotFinder;
import com.enrola.salesagent.scheduling.SlotFinder.Suggestion;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The model's tools: find times, book, reschedule, cancel. Definitions are sent with every
 * request; calls are run here against the live conversation.
 *
 * <p>Each call runs in its own short transaction on a freshly loaded conversation, because it runs
 * during the model call, between the turn's transactions, and a booking is a real side effect that
 * must be saved. Times go to the model with a label in the lead's own time, and come back as the
 * exact instant it was offered, so the model never does time-zone arithmetic.
 *
 * <p>Bad arguments or a rule broken (booking twice, cancelling nothing) come back to the model as
 * {@code {"error": ...}} so it can correct itself, rather than failing the turn.
 */
@Component
public class BookingTools {

	private static final Logger log = LoggerFactory.getLogger(BookingTools.class);
	private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("EEEE d MMMM, h:mm a", Locale.UK);

	private final ConversationRepository conversations;
	private final BookingService bookings;
	private final SlotFinder slots;
	private final JsonMapper json;
	private final TransactionTemplate tx;

	BookingTools(ConversationRepository conversations, BookingService bookings, SlotFinder slots, JsonMapper json,
			PlatformTransactionManager transactions) {
		this.conversations = conversations;
		this.bookings = bookings;
		this.slots = slots;
		this.json = json;
		this.tx = new TransactionTemplate(transactions);
	}

	/** Function definitions in the Responses API's format; strict, so arguments always parse. */
	public List<Map<String, Object>> definitions() {
		return List.of(
				function("get_available_slots",
						"Find up to 3 free call times near the day and time window the lead asked for, in the lead's local time. "
								+ "Use the lead's local date and time to work out what 'today', 'tomorrow' or 'Monday' means.",
						Map.of(
								"day", stringParam("The day the lead asked about, as YYYY-MM-DD."),
								"from", stringParam("Start of the window they'd like, as HH:MM (24-hour). 00:00 for any time."),
								"to", stringParam("End of the window, as HH:MM (24-hour). 23:59 for any time.")),
						List.of("day", "from", "to")),
				function("book_call",
						"Book the 15-minute call at a time the lead chose. Only use a start_time returned by get_available_slots.",
						Map.of("start_time", stringParam("The chosen slot's start_time, exactly as get_available_slots returned it.")),
						List.of("start_time")),
				function("reschedule_call",
						"Move an existing booking to a new time the lead chose. Only use a start_time returned by get_available_slots.",
						Map.of("start_time", stringParam("The new slot's start_time, exactly as get_available_slots returned it.")),
						List.of("start_time")),
				function("cancel_call",
						"Cancel the lead's booked call, when they ask to cancel outright.",
						Map.of(),
						List.of()));
	}

	/** Runs one tool call and returns its result as JSON for the model. */
	public String execute(String name, String arguments, Long conversationId) {
		Map<String, Object> result;
		try {
			JsonNode args = json.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
			result = tx.execute(status -> {
				Conversation conversation = conversations.findWithMessagesById(conversationId)
						.orElseThrow(() -> new NoSuchElementException("No conversation " + conversationId));
				return switch (name) {
					case "get_available_slots" -> availableSlots(conversation, args);
					case "book_call" -> book(conversation, args);
					case "reschedule_call" -> reschedule(conversation, args);
					case "cancel_call" -> cancel(conversation);
					default -> error("Unknown tool " + name);
				};
			});
		}
		catch (JacksonException | DateTimeParseException | IllegalArgumentException e) {
			result = error("Bad arguments: " + e.getMessage());
		}
		log.info("Tool {}({}) for conversation {} -> {}", name, arguments, conversationId, result);
		return json.writeValueAsString(result);
	}

	private Map<String, Object> availableSlots(Conversation conversation, JsonNode args) {
		ZoneId zone = zone(conversation);
		Suggestion suggestion = slots.suggest(zone,
				LocalDate.parse(args.path("day").asString()),
				LocalTime.parse(args.path("from").asString()),
				LocalTime.parse(args.path("to").asString()));
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("slots", suggestion.slots().stream().map(slot -> slot(slot, zone)).toList());
		result.put("matches_preference", suggestion.matchesPreference());
		return result;
	}

	private Map<String, Object> book(Conversation conversation, JsonNode args) {
		if (conversation.getBooking() != null) {
			return error("Already booked for " + label(conversation.getBooking().getStartTime(), zone(conversation))
					+ ". Use reschedule_call to move it.");
		}
		return outcome(conversation, bookings.book(conversation, Instant.parse(args.path("start_time").asString())));
	}

	private Map<String, Object> reschedule(Conversation conversation, JsonNode args) {
		if (conversation.getBooking() == null) {
			return error("Nothing is booked yet. Use book_call.");
		}
		return outcome(conversation, bookings.reschedule(conversation, Instant.parse(args.path("start_time").asString())));
	}

	private Map<String, Object> cancel(Conversation conversation) {
		if (conversation.getBooking() == null) {
			return error("Nothing is booked, so there's nothing to cancel.");
		}
		bookings.cancel(conversation);
		return Map.of("status", "cancelled");
	}

	private Map<String, Object> outcome(Conversation conversation, Result result) {
		ZoneId zone = zone(conversation);
		return switch (result) {
			case Booked booked -> {
				Booking booking = booked.booking();
				yield Map.of("status", "booked", "time", label(booking.getStartTime(), zone),
						"advisor", booking.getAdvisorName(),
						"next", "Set reply to null: the system sends the lead a confirmation.");
			}
			case Unavailable unavailable -> Map.of("status", "unavailable",
					"alternatives", unavailable.alternatives().stream().map(slot -> slot(slot, zone)).toList());
		};
	}

	private static Map<String, Object> slot(Instant start, ZoneId zone) {
		return Map.of("start_time", start.toString(), "label", label(start, zone));
	}

	private static String label(Instant instant, ZoneId zone) {
		return instant.atZone(zone).format(LABEL);
	}

	private static ZoneId zone(Conversation conversation) {
		return conversation.getLead().getState().getZone();
	}

	private static Map<String, Object> error(String message) {
		return Map.of("error", message);
	}

	private static Map<String, Object> function(String name, String description, Map<String, Object> properties,
			List<String> required) {
		Map<String, Object> parameters = new LinkedHashMap<>();
		parameters.put("type", "object");
		parameters.put("properties", properties);
		parameters.put("required", required);
		parameters.put("additionalProperties", false);

		Map<String, Object> function = new LinkedHashMap<>();
		function.put("type", "function");
		function.put("name", name);
		function.put("description", description);
		function.put("parameters", parameters);
		function.put("strict", true);
		return function;
	}

	private static Map<String, Object> stringParam(String description) {
		return Map.of("type", "string", "description", description);
	}

}
