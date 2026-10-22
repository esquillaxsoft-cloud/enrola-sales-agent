package com.enrola.salesagent.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Instant;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

import com.enrola.salesagent.lead.AuState;
import com.enrola.salesagent.lead.Lead;

/**
 * Runs against the Flyway schema (Hibernate only validates), so these tests also prove the
 * entity mappings and the SQL agree. Uses the test profile's H2 settings (PostgreSQL mode)
 * rather than letting @DataJpaTest swap in a default-mode embedded database.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ConversationRepositoryTest {

	private static final Instant T0 = Instant.parse("2026-07-26T23:00:00Z");

	@Autowired
	ConversationRepository conversations;

	@Autowired
	TestEntityManager em;

	Lead lauren;

	@BeforeEach
	void seedLead() {
		lauren = em.persist(new Lead("lead-1", "Lauren", "+61457099878", "lauren@example.com", AuState.NSW,
				"Bupa", null, T0));
	}

	@Test
	void conversationRoundTripsWithMessagesInOrder() {
		Conversation conversation = new Conversation(lauren, "comparato/v1", Locale.forLanguageTag("en-AU"), T0);
		conversation.append(Message.fromTemplate("opener-known-provider", "Hi Lauren, it's Anna from Comparato.", T0));
		Message inbound = Message.fromLead("All of the above", T0.plusSeconds(60));
		inbound.recordAgentOutput("{\"objection\":false}");
		conversation.append(inbound);
		conversation.append(Message.fromModel("Fair enough - a lot of people want both.", T0.plusSeconds(65)));
		Long id = conversations.saveAndFlush(conversation).getId();
		em.clear();

		Conversation reloaded = conversations.findWithMessagesById(id).orElseThrow();

		assertThat(reloaded.getLead().getGivenName()).isEqualTo("Lauren");
		assertThat(reloaded.getStatus()).isEqualTo(ConversationStatus.ACTIVE);
		assertThat(reloaded.getLocale()).isEqualTo(Locale.forLanguageTag("en-AU"));
		assertThat(reloaded.getAgentVersion()).isEqualTo("comparato/v1");
		assertThat(reloaded.getBooking()).isNull();
		assertThat(reloaded.isGoalMet()).isFalse();
		assertThat(reloaded.isUnsubscribed()).isFalse();
		assertThat(reloaded.getMessages())
				.extracting(Message::getSender, Message::getTemplateKey, Message::getAgentOutput)
				.containsExactly(
						tuple(Sender.AGENT, "opener-known-provider", null),
						tuple(Sender.LEAD, null, "{\"objection\":false}"),
						tuple(Sender.AGENT, null, null));
	}

	@Test
	void bookingRoundTripsAndMeansGoalMet() {
		Conversation conversation = new Conversation(lauren, "comparato/v1", Locale.forLanguageTag("en-AU"), T0);
		Instant monday12 = Instant.parse("2026-07-27T02:00:00Z");
		conversation.book(new Booking("evt-123", monday12, "Sam Nguyen"));
		Long id = conversations.saveAndFlush(conversation).getId();
		em.clear();

		Conversation reloaded = conversations.findById(id).orElseThrow();

		assertThat(reloaded.getStatus()).isEqualTo(ConversationStatus.BOOKED);
		assertThat(reloaded.isGoalMet()).isTrue();
		assertThat(reloaded.getBooking().getEventId()).isEqualTo("evt-123");
		assertThat(reloaded.getBooking().getStartTime()).isEqualTo(monday12);
		assertThat(reloaded.getBooking().getAdvisorName()).isEqualTo("Sam Nguyen");
	}

	@Test
	void aLeadHasAtMostOneConversation() {
		conversations.saveAndFlush(new Conversation(lauren, "comparato/v1", Locale.forLanguageTag("en-AU"), T0));

		assertThatThrownBy(() -> conversations
				.saveAndFlush(new Conversation(lauren, "comparato/v1", Locale.forLanguageTag("en-AU"), T0)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void concurrentTurnsOnTheSameConversationCannotBothCommit() {
		Long id = conversations.saveAndFlush(
				new Conversation(lauren, "comparato/v1", Locale.forLanguageTag("en-AU"), T0)).getId();
		em.clear();

		// Two turns (e.g. two SMS arriving together) each load the conversation...
		Conversation firstTurn = conversations.findWithMessagesById(id).orElseThrow();
		em.clear();
		Conversation secondTurn = conversations.findWithMessagesById(id).orElseThrow();
		em.clear();

		// ...the first commits...
		firstTurn.book(new Booking("evt-1", T0.plusSeconds(3600), "Sam Nguyen"));
		conversations.saveAndFlush(firstTurn);
		em.clear();

		// ...so the second is working from stale state and must not overwrite it.
		secondTurn.append(Message.fromLead("actually not interested", T0.plusSeconds(120)));
		assertThatThrownBy(() -> conversations.saveAndFlush(secondTurn))
				.isInstanceOf(ObjectOptimisticLockingFailureException.class);
	}

}
