package com.enrola.salesagent.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Instant;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.enrola.salesagent.conversation.ConversationStarter.Outcome;
import com.enrola.salesagent.conversation.ConversationStarter.StartResult;
import com.enrola.salesagent.lead.Lead;
import com.enrola.salesagent.lead.LeadService;

/** Uses the sample leads behind the stub lead API; each test rolls back. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ConversationStarterTest {

	@Autowired
	LeadService leadService;

	@Autowired
	ConversationStarter starter;

	@Test
	void startsAConversationWithTheOpener() {
		StartResult result = starter.start(leadService.ingest("lead-john"));

		assertThat(result.conversation().getMessages())
				.extracting(Message::getSender, Message::getTemplateKey)
				.containsExactly(tuple(Sender.AGENT, "opener-known-provider"));
	}

	@Test
	void aDuplicateNumberGetsNoSecondOpener() {
		Conversation johns = starter.start(leadService.ingest("lead-john")).conversation();

		starter.start(leadService.ingest("lead-john-again"));

		assertThat(johns.getMessages()).hasSize(1);
	}

	@Test
	void startsAConversationPinnedToTheCurrentAgentVersionAndLocale() {
		StartResult result = starter.start(leadService.ingest("lead-john"));

		assertThat(result.outcome()).isEqualTo(Outcome.STARTED);
		assertThat(result.conversation().getStatus()).isEqualTo(ConversationStatus.ACTIVE);
		assertThat(result.conversation().getAgentVersion()).isEqualTo("comparato/v1");
		assertThat(result.conversation().getLocale()).isEqualTo(Locale.forLanguageTag("en-AU"));
	}

	@Test
	void ingestingAndStartingTheSameLeadTwiceIsIdempotent() {
		Conversation first = starter.start(leadService.ingest("lead-john")).conversation();

		StartResult again = starter.start(leadService.ingest("lead-john"));

		assertThat(again.outcome()).isEqualTo(Outcome.ALREADY_STARTED);
		assertThat(again.conversation().getId()).isEqualTo(first.getId());
	}

	@Test
	void aDuplicateLeadForTheSameNumberJoinsTheOpenConversation() {
		Conversation johns = starter.start(leadService.ingest("lead-john")).conversation();

		// Same person, new lead id, number in local format.
		Lead johnAgain = leadService.ingest("lead-john-again");
		StartResult result = starter.start(johnAgain);

		assertThat(johnAgain.getPhone()).isEqualTo("+61457099876");
		assertThat(result.outcome()).isEqualTo(Outcome.SAME_NUMBER_OPEN);
		assertThat(result.conversation().getId()).isEqualTo(johns.getId());
	}

	@Test
	void aBookedConversationStillCountsAsOpen() {
		Conversation johns = starter.start(leadService.ingest("lead-john")).conversation();
		johns.book(new Booking("evt-1", Instant.parse("2026-07-27T02:00:00Z"), "Sam Nguyen"));

		StartResult result = starter.start(leadService.ingest("lead-john-again"));

		assertThat(result.outcome()).isEqualTo(Outcome.SAME_NUMBER_OPEN);
	}

	@Test
	void differentPeopleGetTheirOwnConversations() {
		Conversation johns = starter.start(leadService.ingest("lead-john")).conversation();

		StartResult janes = starter.start(leadService.ingest("lead-jane"));

		assertThat(janes.outcome()).isEqualTo(Outcome.STARTED);
		assertThat(janes.conversation().getId()).isNotEqualTo(johns.getId());
	}

}
