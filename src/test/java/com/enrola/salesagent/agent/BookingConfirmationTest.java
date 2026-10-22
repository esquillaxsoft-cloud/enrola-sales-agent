package com.enrola.salesagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import com.enrola.salesagent.agent.AgentOutput.Stage;
import com.enrola.salesagent.agent.TurnService.TurnResult;
import com.enrola.salesagent.conversation.ConversationStarter;
import com.enrola.salesagent.conversation.Message;
import com.enrola.salesagent.scheduling.CalendlyClient;

/** A model that books during its turn: the lead gets the code-written confirmation, not the model's words. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BookingConfirmationTest {

	@Autowired
	ConversationStarter starter;

	@Autowired
	TurnService turns;

	@Autowired
	BookingTools tools;

	@Autowired
	CalendlyClient calendly;

	@Autowired
	Clock clock;

	@MockitoSpyBean
	AgentReplier agent;

	@Test
	void aCallBookedByTheModelIsConfirmedFromTheTemplate() {
		Long id = starter.startLead("lead-john").conversation().getId();
		// Read the clock once: two reads can be nanoseconds apart, making the range just over 7 days.
		Instant now = clock.instant();
		Instant slot = calendly.availableStartTimes(now, now.plus(Duration.ofDays(7))).getFirst();
		doAnswer(invocation -> {
			tools.execute("book_call", "{\"start_time\":\"%s\"}".formatted(slot), id);
			return AgentTurn.of(AgentOutput.reply("Done! See you then!", Stage.BOOKED, "booked"));
		}).when(agent).reply(any());

		TurnResult result = turns.handleInbound(id, "the first one works");

		Message sent = result.reply().orElseThrow();
		assertThat(sent.getTemplateKey()).isEqualTo("booking-confirmed");
		assertThat(sent.getBody())
				.startsWith("Booked - you're in for ")
				.contains(result.conversation().getBooking().getAdvisorName())
				.doesNotContain("Done!");
		assertThat(result.conversation().isGoalMet()).isTrue();
	}

	@Test
	void aTurnThatDoesntBookSendsTheModelsReplyAsUsual() {
		Long id = starter.startLead("lead-john").conversation().getId();
		doAnswer(invocation -> AgentTurn.of(AgentOutput.reply("Are you free Thursday arvo?", Stage.SUGGEST_CALL, "ask")))
				.when(agent).reply(any());

		TurnResult result = turns.handleInbound(id, "maybe");

		assertThat(result.reply()).get().extracting(Message::getBody).isEqualTo("Are you free Thursday arvo?");
	}

}
