package com.enrola.salesagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import com.enrola.salesagent.agent.TurnService.TurnResult;
import com.enrola.salesagent.conversation.ConversationStarter;
import com.enrola.salesagent.conversation.ConversationStatus;
import com.enrola.salesagent.conversation.EndReason;

/** Abuse caught by moderation ends the conversation before the model is asked. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ModerationTest {

	@Autowired
	ConversationStarter starter;

	@Autowired
	TurnService turns;

	@MockitoBean
	Moderator moderator;

	@MockitoSpyBean
	AgentReplier agent;

	@Test
	void flaggedAbuseEndsTheConversationWithoutAskingTheModel() {
		Long id = starter.startLead("lead-john").conversation().getId();
		when(moderator.abusiveCategories(anyString())).thenReturn(List.of("harassment"));

		TurnResult result = turns.handleInbound(id, "(something abusive)");

		assertThat(result.reply()).isEmpty();
		assertThat(result.conversation().getStatus()).isEqualTo(ConversationStatus.ENDED);
		assertThat(result.conversation().getEndReason()).isEqualTo(EndReason.ABUSIVE);
		assertThat(result.conversation().getMessages().getLast().getAgentOutput()).contains("harassment");
		verify(agent, never()).reply(any());
	}

	@Test
	void unflaggedMessagesGoToTheModel() {
		Long id = starter.startLead("lead-john").conversation().getId();
		when(moderator.abusiveCategories(anyString())).thenReturn(List.of());

		TurnResult result = turns.handleInbound(id, "my husband just had a heart attack, we need better cover");

		assertThat(result.reply()).isPresent();
		assertThat(result.conversation().getStatus()).isEqualTo(ConversationStatus.ACTIVE);
	}

}
