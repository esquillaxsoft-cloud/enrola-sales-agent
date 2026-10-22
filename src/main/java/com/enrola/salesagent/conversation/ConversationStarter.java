package com.enrola.salesagent.conversation;

import java.time.Clock;
import java.util.EnumSet;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enrola.salesagent.config.AgentProperties;
import com.enrola.salesagent.lead.Lead;
import com.enrola.salesagent.lead.LeadService;

/**
 * Starts the agent's conversation with a lead, while making sure one person (one phone number)
 * is never in two conversations at once.
 */
@Service
public class ConversationStarter {

	/**
	 * A person in one of these is still talking to us, so a duplicate lead for the same number
	 * must not start a second conversation. After DECLINED or ENDED, a new form submission counts
	 * as fresh interest. (Opt-outs are blocked per number by the guardrails, commit 8.)
	 */
	static final Set<ConversationStatus> OPEN = EnumSet.of(ConversationStatus.ACTIVE, ConversationStatus.BOOKED);

	public enum Outcome {
		STARTED,
		/** This lead already has a conversation; starting is idempotent. */
		ALREADY_STARTED,
		/** Another lead with the same phone number has an open conversation; no new one started. */
		SAME_NUMBER_OPEN
	}

	/**
	 * @param lead         the lead that was asked for (may differ from the conversation's lead when
	 *                     another lead with the same number owns the open conversation)
	 * @param conversation the conversation this lead is now in
	 */
	public record StartResult(Lead lead, Conversation conversation, Outcome outcome) {

		/** The opener, when this call started the conversation; nothing to send otherwise. */
		public Optional<Message> messageToSend() {
			return outcome == Outcome.STARTED ? Optional.of(conversation.getMessages().getFirst()) : Optional.empty();
		}

	}

	private final ConversationRepository conversations;
	private final LeadService leadService;
	private final Opener opener;
	private final AgentProperties agent;
	private final Clock clock;

	ConversationStarter(ConversationRepository conversations, LeadService leadService, Opener opener,
			AgentProperties agent, Clock clock) {
		this.conversations = conversations;
		this.leadService = leadService;
		this.opener = opener;
		this.agent = agent;
		this.clock = clock;
	}

	/**
	 * Brings the lead in from the lead API and starts its conversation, in one transaction: if
	 * starting fails, the lead isn't left half-ingested.
	 *
	 * @throws NoSuchElementException   if the lead API doesn't know the lead
	 * @throws IllegalArgumentException if the lead's data is unusable (e.g. invalid phone)
	 */
	@Transactional
	public StartResult startLead(String externalLeadId) {
		return start(leadService.ingest(externalLeadId));
	}

	@Transactional
	public StartResult start(Lead lead) {
		Optional<Conversation> own = conversations.findByLeadId(lead.getId());
		if (own.isPresent()) {
			return new StartResult(lead, own.get(), Outcome.ALREADY_STARTED);
		}
		Optional<Conversation> open = conversations.findFirstByLeadPhoneAndStatusInOrderByIdDesc(lead.getPhone(), OPEN);
		if (open.isPresent()) {
			return new StartResult(lead, open.get(), Outcome.SAME_NUMBER_OPEN);
		}
		Conversation conversation = new Conversation(lead, agent.version(), agent.defaultLocale(), clock.instant());
		conversation.append(opener.compose(conversation, clock.instant()));
		return new StartResult(lead, conversations.save(conversation), Outcome.STARTED);
	}

	/**
	 * Testing aid for the UI: throw a conversation away and start the lead again from scratch,
	 * on the current agent version. (Once bookings exist, this must also cancel the booking.)
	 */
	@Transactional
	public Conversation restart(Long conversationId) {
		Conversation old = conversations.findById(conversationId)
				.orElseThrow(() -> new NoSuchElementException("No conversation " + conversationId));
		Lead lead = old.getLead();
		conversations.delete(old);
		// Hibernate runs inserts before deletes at flush, which would trip the one-conversation-per-lead
		// constraint, so delete explicitly first.
		conversations.flush();
		return start(lead).conversation();
	}

}
