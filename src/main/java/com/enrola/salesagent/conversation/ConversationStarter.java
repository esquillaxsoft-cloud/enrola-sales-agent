package com.enrola.salesagent.conversation;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enrola.salesagent.config.AgentProperties;
import com.enrola.salesagent.lead.Lead;

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

	public record StartResult(Conversation conversation, Outcome outcome) {
	}

	private final ConversationRepository conversations;
	private final AgentProperties agent;
	private final Clock clock;

	ConversationStarter(ConversationRepository conversations, AgentProperties agent, Clock clock) {
		this.conversations = conversations;
		this.agent = agent;
		this.clock = clock;
	}

	@Transactional
	public StartResult start(Lead lead) {
		Optional<Conversation> own = conversations.findByLeadId(lead.getId());
		if (own.isPresent()) {
			return new StartResult(own.get(), Outcome.ALREADY_STARTED);
		}
		Optional<Conversation> open = conversations.findFirstByLeadPhoneAndStatusInOrderByIdDesc(lead.getPhone(), OPEN);
		if (open.isPresent()) {
			return new StartResult(open.get(), Outcome.SAME_NUMBER_OPEN);
		}
		Conversation conversation = new Conversation(lead, agent.version(), agent.defaultLocale(), clock.instant());
		return new StartResult(conversations.save(conversation), Outcome.STARTED);
	}

}
