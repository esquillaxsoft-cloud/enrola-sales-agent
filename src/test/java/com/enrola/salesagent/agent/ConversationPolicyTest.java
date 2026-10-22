package com.enrola.salesagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.enrola.salesagent.agent.AgentOutput.Stage;
import com.enrola.salesagent.assets.AgentAssets;
import com.enrola.salesagent.config.AgentProperties;
import com.enrola.salesagent.conversation.Conversation;
import com.enrola.salesagent.conversation.ConversationStatus;
import com.enrola.salesagent.conversation.EndReason;
import com.enrola.salesagent.conversation.Message;
import com.enrola.salesagent.conversation.OptOuts;
import com.enrola.salesagent.conversation.Sender;
import com.enrola.salesagent.lead.AuState;
import com.enrola.salesagent.lead.Lead;

/** Hand-written model outputs in, state changes and the SMS to send out. No model, no Spring. */
class ConversationPolicyTest {

	static final Locale EN_AU = Locale.forLanguageTag("en-AU");
	static final Instant NOW = Instant.parse("2026-07-26T23:00:00Z");

	OptOuts optOuts = mock(OptOuts.class);
	ConversationPolicy policy = new ConversationPolicy(optOuts,
			new AgentAssets(new AgentProperties("comparato/v1", EN_AU)));

	Conversation conversation = new Conversation(
			new Lead("lead-john", "John", "+61457099876", null, AuState.WA, "HBF", null, NOW),
			"comparato/v1", EN_AU, NOW);

	@Test
	void aPlainReplyIsSentAsWrittenAndChangesNothing() {
		Optional<Message> sent = policy.apply(conversation, reply("Fair enough. Hospital, extras or both?"), NOW);

		assertThat(sent).get().satisfies(m -> {
			assertThat(m.getSender()).isEqualTo(Sender.AGENT);
			assertThat(m.getBody()).isEqualTo("Fair enough. Hospital, extras or both?");
			assertThat(m.getTemplateKey()).isNull();
		});
		assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.ACTIVE);
	}

	@Test
	void aNullReplyMeansSilence() {
		assertThat(policy.apply(conversation, reply(null), NOW)).isEmpty();
		assertThat(policy.apply(conversation, reply("  "), NOW)).isEmpty();
	}

	@Test
	void theFirstObjectionGetsTheModelsOneRetry() {
		Optional<Message> sent = policy.apply(conversation, objection("Are you sure I can't change your mind?"), NOW);

		assertThat(sent).get().extracting(Message::getBody).isEqualTo("Are you sure I can't change your mind?");
		assertThat(conversation.getObjectionCount()).isEqualTo(1);
		assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.ACTIVE);
	}

	@Test
	void theSecondObjectionDeclinesWithTheFixedGoodbyeNotTheModelsWording() {
		policy.apply(conversation, objection("Are you sure I can't change your mind?"), NOW);

		// The model tries a third time; code doesn't let it.
		Optional<Message> sent = policy.apply(conversation, objection("Last chance: how about Tuesday?"), NOW);

		assertThat(sent).get().satisfies(m -> {
			assertThat(m.getTemplateKey()).isEqualTo("declined");
			assertThat(m.getBody()).isEqualTo(
					"Ok, understood. I'll leave it there, but let me know if you change your mind.");
		});
		assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.DECLINED);
		assertThat(conversation.getStatus().isTerminal()).isTrue();
	}

	@Test
	void aSoftOptOutOptsOutAndIgnoresTheReply() {
		AgentOutput output = new AgentOutput("No worries, I'll stop.", Stage.CLOSING, false, true, false, "asked to stop");

		assertThat(policy.apply(conversation, output, NOW)).isEmpty();
		verify(optOuts).optOut(conversation);
	}

	@Test
	void abuseEndsTheConversationWithoutAReply() {
		AgentOutput output = new AgentOutput("Let's keep it civil.", Stage.CLOSING, false, false, true, "abusive");

		assertThat(policy.apply(conversation, output, NOW)).isEmpty();
		assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.ENDED);
		assertThat(conversation.getEndReason()).isEqualTo(EndReason.ABUSIVE);
		verify(optOuts, never()).optOut(conversation);
	}

	@Test
	void anAbusiveOptOutIsStillRecordedAsAnOptOut() {
		AgentOutput output = new AgentOutput(null, Stage.CLOSING, false, true, true, "abusive, wants us gone");

		assertThat(policy.apply(conversation, output, NOW)).isEmpty();
		verify(optOuts).optOut(conversation);
	}

	static AgentOutput reply(String text) {
		return AgentOutput.reply(text, Stage.PREFERENCE, "test");
	}

	static AgentOutput objection(String text) {
		return new AgentOutput(text, Stage.SUGGEST_CALL, true, false, false, "objection");
	}

}
