package com.enrola.salesagent.agent;

import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import com.enrola.salesagent.config.AgentProperties;
import com.enrola.salesagent.conversation.Conversation;
import com.enrola.salesagent.conversation.ConversationRepository;
import com.enrola.salesagent.conversation.EndReason;
import com.enrola.salesagent.conversation.Message;
import com.enrola.salesagent.conversation.OptOuts;
import com.enrola.salesagent.conversation.Sender;

/**
 * One turn: a lead's SMS comes in, the hard rules decide whether the agent may answer, the agent
 * reports on the turn ({@link AgentOutput}), the {@link ConversationPolicy} applies that report,
 * and everything is stored with the conversation.
 *
 * <p>For now this is one transaction around the agent call. That's fine with a stub, but a model
 * call takes seconds, and holding a database connection that long doesn't scale. When the model
 * arrives (commit 11) the turn splits into: store the inbound message, call the model outside a
 * transaction, store the reply under the conversation's optimistic lock.
 */
@Service
public class TurnService {

	/**
	 * @param reply     the agent's SMS to send, or empty when it stays silent
	 * @param duplicate true when this inbound SMS was seen before (a webhook retry); nothing new
	 *                  happened and {@code reply} is the reply sent the first time
	 */
	public record TurnResult(Conversation conversation, Optional<Message> reply, boolean duplicate) {
	}

	private final ConversationRepository conversations;
	private final AgentReplier replier;
	private final ConversationPolicy policy;
	private final OptOutKeywords optOutKeywords;
	private final OptOuts optOuts;
	private final AgentProperties agent;
	private final JsonMapper json;
	private final Clock clock;

	TurnService(ConversationRepository conversations, AgentReplier replier, ConversationPolicy policy,
			OptOutKeywords optOutKeywords, OptOuts optOuts, AgentProperties agent, JsonMapper json, Clock clock) {
		this.conversations = conversations;
		this.replier = replier;
		this.policy = policy;
		this.optOutKeywords = optOutKeywords;
		this.optOuts = optOuts;
		this.agent = agent;
		this.json = json;
		this.clock = clock;
	}

	/** An SMS typed into the prototype UI, with no platform message id. */
	@Transactional
	public TurnResult handleInbound(Long conversationId, String body) {
		return handleInbound(conversationId, null, body);
	}

	/**
	 * @param platformMessageId the platform's id for this SMS; a repeat of an id already handled
	 *                          returns the original outcome without running the agent again
	 */
	@Transactional
	public TurnResult handleInbound(Long conversationId, String platformMessageId, String body) {
		Conversation conversation = conversations.findWithMessagesById(conversationId)
				.orElseThrow(() -> new NoSuchElementException("No conversation " + conversationId));

		if (platformMessageId != null) {
			Optional<TurnResult> retry = previousOutcome(conversation, platformMessageId);
			if (retry.isPresent()) {
				return retry.get();
			}
		}

		String text = body.strip();
		// Always stored, even when the agent won't answer: it's the lead's message and our record.
		Message inbound = Message.fromLead(platformMessageId, text, clock.instant());
		conversation.append(inbound);

		if (!mayReply(conversation, text)) {
			return new TurnResult(conversation, Optional.empty(), false);
		}

		AgentOutput output = replier.reply(conversation);
		inbound.recordAgentOutput(json.writeValueAsString(output));
		Optional<Message> sent = policy.apply(conversation, output, clock.instant());
		sent.ifPresent(conversation::append);
		return new TurnResult(conversation, sent, false);
	}

	/**
	 * The hard rules, checked in code before the model is involved, so none of them depends on a
	 * prompt behaving. May change the conversation's state.
	 */
	private boolean mayReply(Conversation conversation, String text) {
		// Opted out, declined or ended: never send anything again.
		if (conversation.getStatus().isTerminal()) {
			return false;
		}
		// Spam Act: an explicit opt-out is honoured at once, with no reply and no model call.
		if (optOutKeywords.isOptOut(conversation, text)) {
			optOuts.optOut(conversation);
			return false;
		}
		// Backstop against reply loops (e.g. "thanks!" / "you too!" forever).
		if (conversation.agentMessageCount() >= agent.maxAgentMessages()) {
			conversation.end(EndReason.MESSAGE_CAP);
			return false;
		}
		return true;
	}

	/** The reply that followed this inbound SMS the first time, if we've seen it. */
	private static Optional<TurnResult> previousOutcome(Conversation conversation, String platformMessageId) {
		List<Message> messages = conversation.getMessages();
		for (int i = 0; i < messages.size(); i++) {
			if (Objects.equals(messages.get(i).getPlatformMessageId(), platformMessageId)) {
				Optional<Message> reply = i + 1 < messages.size() && messages.get(i + 1).getSender() == Sender.AGENT
						? Optional.of(messages.get(i + 1))
						: Optional.empty();
				return Optional.of(new TurnResult(conversation, reply, true));
			}
		}
		return Optional.empty();
	}

}
