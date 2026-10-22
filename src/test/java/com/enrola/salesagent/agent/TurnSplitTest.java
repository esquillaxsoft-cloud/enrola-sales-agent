package com.enrola.salesagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import com.enrola.salesagent.agent.AgentOutput.Stage;
import com.enrola.salesagent.agent.TurnService.TurnResult;
import com.enrola.salesagent.conversation.Conversation;
import com.enrola.salesagent.conversation.ConversationRepository;
import com.enrola.salesagent.conversation.ConversationStarter;
import com.enrola.salesagent.conversation.ConversationStatus;
import com.enrola.salesagent.conversation.Message;

/** What happens around the model call, now that it runs between two transactions. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TurnSplitTest {

	@Autowired
	ConversationStarter starter;

	@Autowired
	TurnService turns;

	@Autowired
	ConversationRepository conversations;

	@MockitoSpyBean
	AgentReplier agent;

	Long id;

	@BeforeEach
	void startJohn() {
		id = starter.startLead("lead-john").conversation().getId();
	}

	@Test
	void aNewerSmsArrivingDuringTheModelCallSupersedesThisReply() {
		doAnswer(invocation -> {
			// The lead texts again while the model is thinking about their first message.
			Conversation c = conversations.findWithMessagesById(id).orElseThrow();
			c.append(Message.fromLead("actually, mornings are best", Instant.now()));
			conversations.flush();
			return AgentTurn.of(AgentOutput.reply("Hospital, extras or both?", Stage.PREFERENCE, "first message"));
		}).when(agent).reply(any());

		TurnResult result = turns.handleInbound(id, "save money");

		assertThat(result.reply()).isEmpty();
		// The output is still recorded against the message it was about.
		assertThat(result.conversation().getMessages().get(1).getAgentOutput()).contains("first message");
	}

	@Test
	void anOptOutStillCountsEvenWhenTheReplyIsSuperseded() {
		doAnswer(invocation -> {
			Conversation c = conversations.findWithMessagesById(id).orElseThrow();
			c.append(Message.fromLead("hello?", Instant.now()));
			conversations.flush();
			return AgentTurn.of(new AgentOutput("No worries.", Stage.CLOSING, false, true, false, "please don't text me"));
		}).when(agent).reply(any());

		TurnResult result = turns.handleInbound(id, "please don't text me again");

		assertThat(result.reply()).isEmpty();
		assertThat(result.conversation().getStatus()).isEqualTo(ConversationStatus.OPTED_OUT);
	}

	@Test
	void ifTheModelFailsARetryOfTheSameSmsResumesTheTurn() {
		doThrow(new AgentUnavailableException("timeout"))
				.doReturn(AgentTurn.of(AgentOutput.reply("Hospital, extras or both?", Stage.PREFERENCE, "retry")))
				.when(agent).reply(any());

		assertThatThrownBy(() -> turns.handleInbound(id, "sms-1", "save money"))
				.isInstanceOf(AgentUnavailableException.class);
		TurnResult retry = turns.handleInbound(id, "sms-1", "save money");

		assertThat(retry.duplicate()).isFalse();
		assertThat(retry.reply()).get().extracting(Message::getBody).isEqualTo("Hospital, extras or both?");
		// The lead's message was stored once, not twice.
		assertThat(retry.conversation().getMessages()).extracting(Message::getBody)
				.containsOnlyOnce("save money");
		verify(agent, times(2)).reply(any());
	}

	@Test
	void aRetryOfAFinishedTurnIsStillADuplicate() {
		turns.handleInbound(id, "sms-1", "save money");

		TurnResult retry = turns.handleInbound(id, "sms-1", "save money");

		assertThat(retry.duplicate()).isTrue();
		verify(agent, times(1)).reply(any());
	}

}
