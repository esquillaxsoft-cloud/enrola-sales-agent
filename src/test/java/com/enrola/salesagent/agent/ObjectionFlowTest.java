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
import com.enrola.salesagent.conversation.Message;

/** "Objects twice, then withdraws" from the guide, through the real turn with the stub agent. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ObjectionFlowTest {

	@Autowired
	ConversationStarter starter;

	@Autowired
	TurnService turns;

	@Test
	void objectsTwiceThenTheAgentLetsGoAndStaysQuiet() {
		Long id = starter.startLead("lead-john").conversation().getId();

		TurnResult first = turns.handleInbound(id, "not interested");
		assertThat(first.reply()).get().extracting(Message::getBody).asString().startsWith("Are you sure");

		TurnResult second = turns.handleInbound(id, "no thanks, really not interested");
		assertThat(second.reply()).get().extracting(Message::getTemplateKey).isEqualTo("declined");
		assertThat(second.conversation().getStatus()).isEqualTo(ConversationStatus.DECLINED);

		assertThat(turns.handleInbound(id, "you too!").reply()).isEmpty();
	}

	@Test
	void theModelsReportIsKeptOnTheLeadMessage() {
		Long id = starter.startLead("lead-john").conversation().getId();

		TurnResult result = turns.handleInbound(id, "not interested");

		Message inbound = result.conversation().getMessages().get(1);
		assertThat(inbound.getAgentOutput()).contains("\"objection\":true").contains("\"stage\":\"SUGGEST_CALL\"");
	}

}
