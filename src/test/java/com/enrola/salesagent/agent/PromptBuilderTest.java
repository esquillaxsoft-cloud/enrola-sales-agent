package com.enrola.salesagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.enrola.salesagent.agent.PromptBuilder.InputMessage;
import com.enrola.salesagent.assets.AgentAssets;
import com.enrola.salesagent.config.AgentProperties;
import com.enrola.salesagent.conversation.Booking;
import com.enrola.salesagent.conversation.Conversation;
import com.enrola.salesagent.conversation.Message;
import com.enrola.salesagent.lead.AuState;
import com.enrola.salesagent.lead.Lead;

/** Renders the real comparato/v1 prompt; no model involved. */
class PromptBuilderTest {

	static final Locale EN_AU = Locale.forLanguageTag("en-AU");
	/** Sunday 26 July 2026, 11:00 pm UTC = Monday 7:00 am in Perth. */
	static final Instant NOW = Instant.parse("2026-07-26T23:00:00Z");

	PromptBuilder prompts = new PromptBuilder(new AgentAssets(new AgentProperties("comparato/v1", EN_AU)),
			Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void fillsInTheLeadAndLeavesNoPlaceholders() {
		String instructions = prompts.instructions(conversation("John", AuState.WA, "HBF", "$350-$450"));

		assertThat(instructions)
				.doesNotContain("{{")
				.contains("You are Anna")
				.contains("- Name: John")
				.contains("- Current health insurer: HBF")
				.contains("- Current monthly premium: $350-$450")
				.contains("- Booked call: none yet");
	}

	@Test
	void localTimeIsInTheLeadsTimeZoneSoTodayAndTomorrowMeanTheirs() {
		String instructions = prompts.instructions(conversation("John", AuState.WA, "HBF", null));

		assertThat(instructions).contains("Their local time now: Monday 27 July 2026, 7:00 am");
	}

	@Test
	void missingCustomFieldsAreSaidPlainly() {
		String instructions = prompts.instructions(conversation("Jane", AuState.NSW, null, null));

		assertThat(instructions)
				.contains("- Current health insurer: none")
				.contains("- Current monthly premium: unknown")
				.doesNotContain(": null");
	}

	@Test
	void includesTheInfoPackAndAnyBooking() {
		Conversation conversation = conversation("John", AuState.WA, "HBF", null);
		conversation.book(new Booking("evt-1", Instant.parse("2026-07-27T04:00:00Z"), "Sam Nguyen"));

		assertThat(prompts.instructions(conversation))
				.contains("ABN: 77 666 888 444")
				.contains("- Booked call: Mon 27 Jul 2026, 12:00 pm with Sam Nguyen");
	}

	@Test
	void transcriptMapsOurSmsToAssistantAndTheLeadsToUser() {
		Conversation conversation = conversation("John", AuState.WA, "HBF", null);
		conversation.append(Message.fromTemplate("opener-known-provider", "Hi John, it's Anna.", NOW));
		conversation.append(Message.fromLead("All of the above", NOW));

		assertThat(prompts.input(conversation)).containsExactly(
				new InputMessage("assistant", "Hi John, it's Anna."),
				new InputMessage("user", "All of the above"));
	}

	static Conversation conversation(String name, AuState state, String provider, String premium) {
		Lead lead = new Lead("lead-x", name, "+61457099876", null, state, provider, premium, NOW);
		return new Conversation(lead, "comparato/v1", EN_AU, NOW);
	}

}
