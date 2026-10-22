package com.enrola.salesagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.enrola.salesagent.agent.TurnService.TurnResult;
import com.enrola.salesagent.conversation.ConversationStarter;
import com.enrola.salesagent.conversation.ConversationStatus;
import com.enrola.salesagent.conversation.EndReason;

/** A cap of 3 agent messages: the opener plus two replies, then silence. */
@SpringBootTest(properties = "agent.max-agent-messages=3")
@ActiveProfiles("test")
@Transactional
class MessageCapTest {

	@Autowired
	ConversationStarter starter;

	@Autowired
	TurnService turns;

	@Test
	void theAgentStopsAtTheCapAndEndsTheConversation() {
		Long id = starter.startLead("lead-john").conversation().getId();

		assertThat(turns.handleInbound(id, "thanks!").reply()).isPresent();
		assertThat(turns.handleInbound(id, "you too!").reply()).isPresent();
		TurnResult third = turns.handleInbound(id, "no, you too!");

		assertThat(third.reply()).isEmpty();
		assertThat(third.conversation().getStatus()).isEqualTo(ConversationStatus.ENDED);
		assertThat(third.conversation().getEndReason()).isEqualTo(EndReason.MESSAGE_CAP);
		assertThat(third.conversation().agentMessageCount()).isEqualTo(3);
	}

}
