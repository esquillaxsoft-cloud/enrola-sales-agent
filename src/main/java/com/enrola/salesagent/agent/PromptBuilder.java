package com.enrola.salesagent.agent;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.enrola.salesagent.assets.AgentAssets;
import com.enrola.salesagent.assets.Templates;
import com.enrola.salesagent.conversation.Booking;
import com.enrola.salesagent.conversation.Conversation;
import com.enrola.salesagent.conversation.Sender;
import com.enrola.salesagent.lead.Lead;

/**
 * Builds what the model sees for one turn: the conversation's versioned system prompt with the
 * lead's details filled in, and the whole SMS transcript. Built fresh from our own records every
 * turn (no OpenAI-side threading; README §5).
 */
@Component
public class PromptBuilder {

	/** One message of the transcript, as the Responses API expects it. */
	public record InputMessage(String role, String content) {
	}

	private static final DateTimeFormatter LOCAL_NOW = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a", Locale.UK);
	private static final DateTimeFormatter BOOKED_AT = DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a", Locale.UK);

	private final AgentAssets assets;
	private final Clock clock;

	PromptBuilder(AgentAssets assets, Clock clock) {
		this.assets = assets;
		this.clock = clock;
	}

	public String instructions(Conversation conversation) {
		Lead lead = conversation.getLead();
		ZoneId zone = lead.getState().getZone();
		String template = text(conversation, "system-prompt.md");

		return Templates.render(template, Map.of(
				"givenName", lead.getGivenName(),
				"state", lead.getState() + " (" + zone.getId() + " time)",
				"currentProvider", lead.getCurrentProvider() == null ? "none" : lead.getCurrentProvider(),
				"currentPremium", lead.getCurrentPremium() == null ? "unknown" : lead.getCurrentPremium(),
				"localNow", clock.instant().atZone(zone).format(LOCAL_NOW),
				"booking", describe(conversation.getBooking(), zone),
				"knowledge", text(conversation, "knowledge.md")));
	}

	/** The transcript: our SMS as the assistant's turns, the lead's as the user's. */
	public List<InputMessage> input(Conversation conversation) {
		return conversation.getMessages().stream()
				.map(m -> new InputMessage(m.getSender() == Sender.LEAD ? "user" : "assistant", m.getBody()))
				.toList();
	}

	private String text(Conversation conversation, String name) {
		return assets.text(conversation.getAgentVersion(), conversation.getLocale(), name);
	}

	private static String describe(Booking booking, ZoneId zone) {
		return booking == null
				? "none yet"
				: booking.getStartTime().atZone(zone).format(BOOKED_AT) + " with " + booking.getAdvisorName();
	}

}
