package com.enrola.salesagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import com.enrola.salesagent.agent.TurnService.TurnResult;
import com.enrola.salesagent.conversation.Booking;
import com.enrola.salesagent.conversation.Conversation;
import com.enrola.salesagent.conversation.ConversationStarter;
import com.enrola.salesagent.conversation.ConversationStarter.Outcome;
import com.enrola.salesagent.conversation.ConversationStarter.StartResult;
import com.enrola.salesagent.conversation.ConversationStatus;
import com.enrola.salesagent.conversation.OptOuts;
import com.enrola.salesagent.conversation.Sender;

/**
 * The hard rules that must hold whatever the model does. The agent is a spy, so each test can
 * prove the model was never asked.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GuardrailsTest {

	@Autowired
	ConversationStarter starter;

	@Autowired
	TurnService turns;

	@Autowired
	OptOuts optOuts;

	@MockitoSpyBean
	AgentReplier agent;

	Conversation johns;

	@BeforeEach
	void startJohn() {
		johns = starter.startLead("lead-john").conversation();
	}

	@Test
	void stopOptsOutAtOnceWithNoReplyAndNoModelCall() {
		TurnResult result = turns.handleInbound(johns.getId(), "STOP");

		assertThat(result.reply()).isEmpty();
		assertThat(result.conversation().getStatus()).isEqualTo(ConversationStatus.OPTED_OUT);
		assertThat(result.conversation().isUnsubscribed()).isTrue();
		assertThat(optOuts.isOptedOut("+61457099876")).isTrue();
		verify(agent, never()).reply(any());
	}

	@Test
	void theLeadsStopIsStoredEvenThoughNothingIsSent() {
		TurnResult result = turns.handleInbound(johns.getId(), "Stop.");

		assertThat(result.conversation().getMessages())
				.extracting(m -> m.getSender())
				.containsExactly(Sender.AGENT, Sender.LEAD);
	}

	@Test
	void afterOptingOutNothingIsEverSentAgain() {
		turns.handleInbound(johns.getId(), "stop");
		clearInvocations(agent);

		TurnResult result = turns.handleInbound(johns.getId(), "actually what were you saying?");

		assertThat(result.reply()).isEmpty();
		assertThat(result.conversation().getStatus()).isEqualTo(ConversationStatus.OPTED_OUT);
		verify(agent, never()).reply(any());
	}

	@Test
	void anOptedOutNumberIsNotContactedUnderANewLeadId() {
		turns.handleInbound(johns.getId(), "unsubscribe");

		StartResult again = starter.startLead("lead-john-again");

		assertThat(again.outcome()).isEqualTo(Outcome.NUMBER_OPTED_OUT);
		assertThat(again.messageToSend()).isEmpty();
		assertThat(again.conversation().getStatus()).isEqualTo(ConversationStatus.OPTED_OUT);
		assertThat(again.conversation().getMessages()).isEmpty();
	}

	@Test
	void cancelIsNotAnOptOut() {
		TurnResult result = turns.handleInbound(johns.getId(), "cancel");

		assertThat(result.reply()).isPresent();
		assertThat(result.conversation().getStatus()).isEqualTo(ConversationStatus.ACTIVE);
	}

	@Test
	void aBookedLeadWhoOptsOutStillHasTheirCall() {
		johns.book(new Booking("evt-1", Instant.parse("2026-07-27T02:00:00Z"), "Sam Nguyen"));

		TurnResult result = turns.handleInbound(johns.getId(), "STOP");

		assertThat(result.conversation().isUnsubscribed()).isTrue();
		assertThat(result.conversation().isGoalMet()).isTrue();
	}

	@Test
	void resetForgetsTheOptOutSoTheScenarioCanBeReplayed() {
		turns.handleInbound(johns.getId(), "stop");

		Conversation fresh = starter.restart(johns.getId());

		assertThat(fresh.getStatus()).isEqualTo(ConversationStatus.ACTIVE);
		assertThat(optOuts.isOptedOut("+61457099876")).isFalse();
	}

}
