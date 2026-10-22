package com.enrola.salesagent.agent;

import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enrola.salesagent.conversation.Conversation;
import com.enrola.salesagent.conversation.ConversationRepository;
import com.enrola.salesagent.conversation.Message;
import com.enrola.salesagent.conversation.Sender;

/**
 * One turn: a lead's SMS comes in, the agent replies, and both are stored with the conversation.
 *
 * <p>For now this is one transaction around the agent call. That's fine with a stub, but a model
 * call takes seconds, and holding a database connection that long doesn't scale. When the model
 * arrives (commit 11) the turn splits into: store the inbound message, call the model outside a
 * transaction, store the reply under the conversation's optimistic lock.
 */
@Service
public class TurnService {

	/**
	 * @param reply     the agent's SMS to send, if it replied
	 * @param duplicate true when this inbound SMS was seen before (a webhook retry); nothing new
	 *                  happened and {@code reply} is the reply sent the first time
	 */
	public record TurnResult(Conversation conversation, Optional<Message> reply, boolean duplicate) {
	}

	private final ConversationRepository conversations;
	private final AgentReplier replier;
	private final Clock clock;

	TurnService(ConversationRepository conversations, AgentReplier replier, Clock clock) {
		this.conversations = conversations;
		this.replier = replier;
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

		conversation.append(Message.fromLead(platformMessageId, body.strip(), clock.instant()));
		AgentReply reply = replier.reply(conversation);
		Message sent = Message.fromModel(reply.body(), reply.output(), clock.instant());
		conversation.append(sent);
		return new TurnResult(conversation, Optional.of(sent), false);
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
