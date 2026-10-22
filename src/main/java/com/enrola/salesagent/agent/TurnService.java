package com.enrola.salesagent.agent;

import java.time.Clock;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enrola.salesagent.conversation.Conversation;
import com.enrola.salesagent.conversation.ConversationRepository;
import com.enrola.salesagent.conversation.Message;

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

	private final ConversationRepository conversations;
	private final AgentReplier replier;
	private final Clock clock;

	TurnService(ConversationRepository conversations, AgentReplier replier, Clock clock) {
		this.conversations = conversations;
		this.replier = replier;
		this.clock = clock;
	}

	@Transactional
	public Conversation handleInbound(Long conversationId, String body) {
		Conversation conversation = conversations.findWithMessagesById(conversationId)
				.orElseThrow(() -> new NoSuchElementException("No conversation " + conversationId));

		conversation.append(Message.fromLead(body.strip(), clock.instant()));
		AgentReply reply = replier.reply(conversation);
		conversation.append(Message.fromModel(reply.body(), reply.output(), clock.instant()));
		return conversation;
	}

}
