package com.enrola.salesagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Locale;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.enrola.salesagent.assets.AgentAssets;
import com.enrola.salesagent.config.AgentProperties;
import com.enrola.salesagent.conversation.Conversation;
import com.enrola.salesagent.lead.AuState;
import com.enrola.salesagent.lead.Lead;

/** Against the real comparato/v1 keyword list. */
class OptOutKeywordsTest {

	static final Locale EN_AU = Locale.forLanguageTag("en-AU");

	OptOutKeywords keywords = new OptOutKeywords(new AgentAssets(new AgentProperties("comparato/v1", EN_AU)));

	Conversation conversation = new Conversation(
			new Lead("lead-x", "John", "+61457099876", null, AuState.WA, "HBF", null, Instant.EPOCH),
			"comparato/v1", EN_AU, Instant.EPOCH);

	@ParameterizedTest
	@ValueSource(strings = { "STOP", "stop", "Stop.", "  stop  ", "STOP!!", "Unsubscribe", "opt out", "Opt-out",
			"OPTOUT", "stop all", "End", "quit", "Remove me", "stop texting me" })
	void explicitOptOutsAreRecognised(String message) {
		assertThat(keywords.isOptOut(conversation, message)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "cancel", "Cancel my booking", "don't stop", "can you stop by tuesday?",
			"stop the call on monday and move it", "no", "not interested", "save money" })
	void ordinaryMessagesAreNot(String message) {
		// "cancel" means cancelling a booked call here; "not interested" is an objection for the model.
		assertThat(keywords.isOptOut(conversation, message)).isFalse();
	}

}
