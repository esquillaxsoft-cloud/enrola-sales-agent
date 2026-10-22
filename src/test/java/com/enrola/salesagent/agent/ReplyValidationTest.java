package com.enrola.salesagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.enrola.salesagent.agent.AgentOutput.Stage;
import com.enrola.salesagent.agent.TurnService.TurnResult;
import com.enrola.salesagent.conversation.ConversationRepository;
import com.enrola.salesagent.conversation.ConversationStarter;
import com.enrola.salesagent.conversation.Message;
import com.enrola.salesagent.scheduling.CalendlyClient;

/** What the model writes is checked before it's sent: length (one retry) and plain punctuation. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReplyValidationTest {

	static final String TOO_LONG = "Fair enough. ".repeat(30);

	@Autowired
	ConversationStarter starter;

	@Autowired
	TurnService turns;

	@Autowired
	ConversationRepository conversations;

	@Autowired
	BookingTools tools;

	@Autowired
	CalendlyClient calendly;

	@Autowired
	Clock clock;

	@MockitoBean
	AgentReplier agent;

	Long id;

	@BeforeEach
	void startJohn() {
		id = starter.startLead("lead-john").conversation().getId();
	}

	@Test
	void anOverLongReplyGetsOneRetryWithItsLength() {
		doReturn(reply(TOO_LONG)).when(agent).reply(any());
		doReturn(reply("Hospital, extras or both?")).when(agent).reply(any(), contains("must be at most 320"));

		TurnResult result = turns.handleInbound(id, "save money");

		assertThat(result.reply()).get().extracting(Message::getBody).isEqualTo("Hospital, extras or both?");
		verify(agent).reply(any(), contains(TOO_LONG.strip().length() + " characters"));
	}

	@Test
	void stillTooLongAfterTheRetryFailsTheTurnRatherThanSendingACutOffSms() {
		doReturn(reply(TOO_LONG)).when(agent).reply(any());
		doReturn(reply(TOO_LONG)).when(agent).reply(any(), anyString());

		assertThatThrownBy(() -> turns.handleInbound(id, "save money")).isInstanceOf(AgentUnavailableException.class);

		// The lead's message is kept, and nothing was sent.
		assertThat(conversations.findWithMessagesById(id).orElseThrow().getMessages())
				.extracting(Message::getBody).last().isEqualTo("save money");
	}

	@Test
	void curlyPunctuationIsSentAsPlainText() {
		doReturn(reply("You’re right – it’s free. Hospital, extras or both?")).when(agent).reply(any());

		TurnResult result = turns.handleInbound(id, "is it free?");

		assertThat(result.reply()).get().extracting(Message::getBody)
				.isEqualTo("You're right - it's free. Hospital, extras or both?");
	}

	@Test
	void aBookingTurnIsntRetriedBecauseTheConfirmationReplacesTheReply() {
		Instant now = clock.instant();
		Instant slot = calendly.availableStartTimes(now, now.plus(Duration.ofDays(7))).getFirst();
		doAnswer(invocation -> {
			tools.execute("book_call", "{\"start_time\":\"%s\"}".formatted(slot), id);
			return reply(TOO_LONG);
		}).when(agent).reply(any());

		TurnResult result = turns.handleInbound(id, "the first one");

		assertThat(result.reply()).get().extracting(Message::getTemplateKey).isEqualTo("booking-confirmed");
		verify(agent, never()).reply(any(), anyString());
	}

	static AgentOutput reply(String text) {
		return AgentOutput.reply(text, Stage.PREFERENCE, "test");
	}

}
