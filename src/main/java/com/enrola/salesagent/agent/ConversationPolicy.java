package com.enrola.salesagent.agent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.enrola.salesagent.assets.AgentAssets;
import com.enrola.salesagent.assets.Templates;
import com.enrola.salesagent.conversation.Booking;
import com.enrola.salesagent.conversation.Conversation;
import com.enrola.salesagent.conversation.EndReason;
import com.enrola.salesagent.conversation.Message;
import com.enrola.salesagent.conversation.OptOuts;

/**
 * Turns the model's report on a turn into state changes, and decides what (if anything) is sent.
 * The model flags; this decides. So "try once more, then give up" and "end on abuse" don't rely
 * on the model remembering or complying.
 */
@Component
public class ConversationPolicy {

	/** The brief: after an objection, try one more time; the second objection ends it. */
	static final int OBJECTIONS_BEFORE_GIVING_UP = 2;

	static final String DECLINED_TEMPLATE = "declined";
	static final String BOOKING_CONFIRMED_TEMPLATE = "booking-confirmed";

	/** "Monday 27 July 2026 at 12:00 pm": iOS and Android both offer to add this to the calendar. */
	private static final DateTimeFormatter CONFIRMED_AT =
			DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'at' h:mm a", Locale.UK);

	private final OptOuts optOuts;
	private final AgentAssets assets;

	ConversationPolicy(OptOuts optOuts, AgentAssets assets) {
		this.optOuts = optOuts;
		this.assets = assets;
	}

	/** @return the SMS to send, or empty to stay silent */
	public Optional<Message> apply(Conversation conversation, AgentOutput output, Instant now) {
		// A request to stop texting is honoured whatever else is going on: it's the law.
		if (output.unsubscribe()) {
			optOuts.optOut(conversation);
			return Optional.empty();
		}
		if (output.abusive()) {
			conversation.end(EndReason.ABUSIVE);
			return Optional.empty();
		}
		if (output.objection() && conversation.recordObjection() >= OBJECTIONS_BEFORE_GIVING_UP) {
			conversation.decline();
			// A fixed goodbye, not the model's wording: the last message must not invite a reply.
			return Optional.of(Message.fromTemplate(DECLINED_TEMPLATE, declinedMessage(conversation), now));
		}
		if (output.reply() == null || output.reply().isBlank()) {
			return Optional.empty();
		}
		return Optional.of(Message.fromModel(SmsText.plain(output.reply()).strip(), now));
	}

	/**
	 * For a turn whose reply won't be sent (a newer SMS arrived, or the conversation has ended):
	 * the flags that protect the lead still count. Objections aren't counted; the newer turn sees
	 * the same message and judges it again.
	 */
	public void applyFlagsOnly(Conversation conversation, AgentOutput output) {
		if (output.unsubscribe() && !conversation.isUnsubscribed()) {
			optOuts.optOut(conversation);
		}
		else if (output.abusive() && !conversation.getStatus().isTerminal()) {
			conversation.end(EndReason.ABUSIVE);
		}
	}

	/**
	 * The SMS confirming a call booked (or moved) this turn. Written by code, not the model, so the
	 * time is always in a format phones turn into a calendar event, in the lead's own time.
	 */
	public Message bookingConfirmation(Conversation conversation, Instant now) {
		Booking booking = conversation.getBooking();
		ZoneId zone = conversation.getLead().getState().getZone();
		String template = assets.text(conversation.getAgentVersion(), conversation.getLocale(),
				"sms/" + BOOKING_CONFIRMED_TEMPLATE + ".txt");
		String body = Templates.render(template, Map.of(
				"when", booking.getStartTime().atZone(zone).format(CONFIRMED_AT),
				"advisorName", booking.getAdvisorName()));
		return Message.fromTemplate(BOOKING_CONFIRMED_TEMPLATE, body, now);
	}

	private String declinedMessage(Conversation conversation) {
		String template = assets.text(conversation.getAgentVersion(), conversation.getLocale(),
				"sms/" + DECLINED_TEMPLATE + ".txt");
		return Templates.render(template, Map.of());
	}

}
