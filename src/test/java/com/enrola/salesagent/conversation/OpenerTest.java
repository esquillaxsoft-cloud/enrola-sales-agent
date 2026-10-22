package com.enrola.salesagent.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.enrola.salesagent.assets.AgentAssets;
import com.enrola.salesagent.config.AgentProperties;
import com.enrola.salesagent.lead.AuState;
import com.enrola.salesagent.lead.Lead;
import com.enrola.salesagent.lead.LeadRecord;
import com.enrola.salesagent.lead.SampleLeads;

/** Renders the real comparato/v1 opener templates; no Spring needed. */
class OpenerTest {

	static final Locale EN_AU = Locale.forLanguageTag("en-AU");
	static final Instant NOW = Instant.parse("2026-07-26T23:00:00Z");

	Opener opener = new Opener(new AgentAssets(new AgentProperties("comparato/v1", EN_AU)));

	@Test
	void leadWithAProviderGetsTheProviderAngle() {
		Message message = opener.compose(conversationFor("John", "HBF"), NOW);

		assertThat(message.getTemplateKey()).isEqualTo(Opener.KNOWN_PROVIDER);
		assertThat(message.getBody())
				.startsWith("Hi John, it's Anna from Comparato.")
				.contains("helping a lot of HBF members")
				.endsWith("Reply 'stop' to opt out");
	}

	@Test
	void leadWithNoProviderGetsTheNoProviderOpener() {
		Message message = opener.compose(conversationFor("Jane", null), NOW);

		assertThat(message.getTemplateKey()).isEqualTo(Opener.NO_PROVIDER);
		assertThat(message.getBody())
				.startsWith("Hi Jane, it's Anna from Comparato.")
				.doesNotContain("null")
				.endsWith("Reply 'stop' to opt out");
	}

	@ParameterizedTest
	@MethodSource("sampleLeads")
	void everySampleOpenerFitsTheSmsLimit(LeadRecord sample) {
		String provider = "none".equals(sample.currentProvider()) ? null : sample.currentProvider();
		Message message = opener.compose(conversationFor(sample.givenName(), provider), NOW);

		assertThat(message.getBody().length()).isLessThanOrEqualTo(320);
	}

	static Iterable<LeadRecord> sampleLeads() {
		return new SampleLeads().all();
	}

	private static Conversation conversationFor(String givenName, String provider) {
		Lead lead = new Lead("lead-x", givenName, "+61457099876", null, AuState.NSW, provider, null, NOW);
		return new Conversation(lead, "comparato/v1", EN_AU, NOW);
	}

}
