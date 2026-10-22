package com.enrola.salesagent.agent;

import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.enrola.salesagent.config.AgentProperties;
import com.enrola.salesagent.conversation.Booking;
import com.enrola.salesagent.conversation.Conversation;
import com.enrola.salesagent.conversation.ConversationRepository;
import com.enrola.salesagent.conversation.EndReason;
import com.enrola.salesagent.conversation.Message;
import com.enrola.salesagent.conversation.OptOuts;
import com.enrola.salesagent.conversation.Sender;

import tools.jackson.databind.json.JsonMapper;

/**
 * One turn: a lead's SMS comes in, the hard rules decide whether the agent may answer, the agent
 * reports on the turn ({@link AgentOutput}), the {@link ConversationPolicy} applies that report,
 * and everything is stored with the conversation.
 *
 * <p>A model call takes seconds, so it runs <em>between</em> two short transactions rather than
 * holding a database connection open:
 * <ol>
 * <li>store the lead's message and apply the hard rules;</li>
 * <li>call the model, with no transaction open;</li>
 * <li>store its report and the reply. If another SMS from the lead arrived meanwhile, this reply
 * is dropped (that newer turn answers both); an opt-out or abuse flag still applies.</li>
 * </ol>
 * Two turns for one conversation can't both commit step 3 (optimistic lock on the conversation).
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

	/**
	 * Step 1's outcome: either the turn is already over, or the model is needed for this message.
	 * Remembers the booking as it was, so step 3 can tell whether the model's tools booked a call.
	 */
	private record Prepared(TurnResult finished, Conversation conversation, Long inboundId, String bookingBefore) {

		static Prepared finished(TurnResult result) {
			return new Prepared(result, null, null, null);
		}

		static Prepared needsModel(Conversation conversation, Long inboundId) {
			Booking booking = conversation.getBooking();
			return new Prepared(null, conversation, inboundId, booking == null ? null : booking.getEventId());
		}

	}

	private final ConversationRepository conversations;
	private final AgentReplier replier;
	private final ConversationPolicy policy;
	private final OptOutKeywords optOutKeywords;
	private final OptOuts optOuts;
	private final AgentProperties agent;
	private final JsonMapper json;
	private final TransactionTemplate tx;
	private final Clock clock;

	TurnService(ConversationRepository conversations, AgentReplier replier, ConversationPolicy policy,
			OptOutKeywords optOutKeywords, OptOuts optOuts, AgentProperties agent, JsonMapper json,
			PlatformTransactionManager transactions, Clock clock) {
		this.conversations = conversations;
		this.replier = replier;
		this.policy = policy;
		this.optOutKeywords = optOutKeywords;
		this.optOuts = optOuts;
		this.agent = agent;
		this.json = json;
		this.tx = new TransactionTemplate(transactions);
		this.clock = clock;
	}

	/** An SMS typed into the prototype UI, with no platform message id. */
	public TurnResult handleInbound(Long conversationId, String body) {
		return handleInbound(conversationId, null, body);
	}

	/**
	 * @param platformMessageId the platform's id for this SMS. A repeat of an id already handled
	 *                          returns the original outcome; a repeat of one whose turn failed
	 *                          part-way resumes it.
	 * @throws AgentUnavailableException if the model failed; the lead's message is kept
	 */
	public TurnResult handleInbound(Long conversationId, String platformMessageId, String body) {
		Prepared prepared = tx.execute(status -> prepare(conversationId, platformMessageId, body));
		if (prepared.finished() != null) {
			return prepared.finished();
		}
		AgentOutput output = replier.reply(prepared.conversation());
		return tx.execute(status -> complete(conversationId, prepared, output));
	}

	private Prepared prepare(Long conversationId, String platformMessageId, String body) {
		Conversation conversation = load(conversationId);

		Message inbound = platformMessageId == null ? null : find(conversation, platformMessageId);
		if (inbound != null && isSettled(conversation, inbound)) {
			return Prepared.finished(new TurnResult(conversation, replyAfter(conversation, inbound), true));
		}
		if (inbound == null) {
			// Always stored, even when the agent won't answer: it's the lead's message and our record.
			inbound = Message.fromLead(platformMessageId, body.strip(), clock.instant());
			conversation.append(inbound);
		}

		if (!mayReply(conversation, inbound.getBody())) {
			return Prepared.finished(new TurnResult(conversation, Optional.empty(), false));
		}
		conversations.flush(); // assigns the inbound message's id, used to find it again in step 3
		return Prepared.needsModel(conversation, inbound.getId());
	}

	private TurnResult complete(Long conversationId, Prepared prepared, AgentOutput output) {
		Long inboundId = prepared.inboundId();
		Conversation conversation = load(conversationId);
		Message inbound = conversation.getMessages().stream()
				.filter(m -> inboundId.equals(m.getId()))
				.findFirst()
				.orElseThrow();
		inbound.recordAgentOutput(json.writeValueAsString(output));

		// A booking or reschedule landed during the model call: confirm it with the template, whatever
		// the model wrote, and even if a newer SMS arrived meanwhile (the call is booked either way).
		Booking booking = conversation.getBooking();
		if (booking != null && !booking.getEventId().equals(prepared.bookingBefore())) {
			policy.applyFlagsOnly(conversation, output);
			Optional<Message> sent = conversation.getStatus().isTerminal() || alreadyConfirmed(conversation)
					? Optional.empty()
					: Optional.of(policy.bookingConfirmation(conversation, clock.instant()));
			sent.ifPresent(conversation::append);
			conversations.flush();
			return new TurnResult(conversation, sent, false);
		}

		boolean superseded = conversation.getMessages().stream()
				.anyMatch(m -> m.getSender() == Sender.LEAD && m.getId() != null && m.getId() > inboundId);
		if (superseded || conversation.getStatus().isTerminal()) {
			policy.applyFlagsOnly(conversation, output);
			conversations.flush(); // surface an optimistic-lock clash here, inside the transaction
			return new TurnResult(conversation, Optional.empty(), false);
		}

		Optional<Message> sent = policy.apply(conversation, output, clock.instant());
		sent.ifPresent(conversation::append);
		conversations.flush();
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

	/**
	 * Two overlapping turns can both see the same new booking; only the first confirms it. The
	 * confirmation names the time and advisor, so an identical last message means it's been sent.
	 */
	private boolean alreadyConfirmed(Conversation conversation) {
		Message confirmation = policy.bookingConfirmation(conversation, clock.instant());
		List<Message> messages = conversation.getMessages();
		return messages.reversed().stream()
				.filter(m -> m.getSender() == Sender.AGENT)
				.findFirst()
				.map(last -> last.getBody().equals(confirmation.getBody()))
				.orElse(false);
	}

	private Conversation load(Long conversationId) {
		return conversations.findWithMessagesById(conversationId)
				.orElseThrow(() -> new NoSuchElementException("No conversation " + conversationId));
	}

	private static Message find(Conversation conversation, String platformMessageId) {
		return conversation.getMessages().stream()
				.filter(m -> Objects.equals(m.getPlatformMessageId(), platformMessageId))
				.findFirst()
				.orElse(null);
	}

	/** The turn for this message finished: the model reported, the agent replied, or it's over. */
	private static boolean isSettled(Conversation conversation, Message inbound) {
		return inbound.getAgentOutput() != null
				|| replyAfter(conversation, inbound).isPresent()
				|| conversation.getStatus().isTerminal();
	}

	private static Optional<Message> replyAfter(Conversation conversation, Message inbound) {
		List<Message> messages = conversation.getMessages();
		int i = messages.indexOf(inbound);
		return i + 1 < messages.size() && messages.get(i + 1).getSender() == Sender.AGENT
				? Optional.of(messages.get(i + 1))
				: Optional.empty();
	}

}
