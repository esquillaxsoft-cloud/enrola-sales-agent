package com.enrola.salesagent.agent;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.enrola.salesagent.assets.AgentAssets;
import com.enrola.salesagent.assets.Templates;
import com.enrola.salesagent.conversation.Conversation;
import com.enrola.salesagent.conversation.EndReason;
import com.enrola.salesagent.conversation.Message;
import com.enrola.salesagent.conversation.OptOuts;

/**
 * Turns the model's report on a turn into state changes, and decides what (if anything) is sent.
 * The model flags; this decides. So "try once more, then give up" and "end on abuse" don't rely
 * on the model remembering or complying.
 */
@Component
public class ConversationPolicy {

	/** The brief: after an objection, try one more time; the second objection ends it. */
	static final int OBJECTIONS_BEFORE_GIVING_UP = 2;

	static final String DECLINED_TEMPLATE = "declined";

	private final OptOuts optOuts;
	private final AgentAssets assets;

	ConversationPolicy(OptOuts optOuts, AgentAssets assets) {
		this.optOuts = optOuts;
		this.assets = assets;
	}

	/** @return the SMS to send, or empty to stay silent */
	public Optional<Message> apply(Conversation conversation, AgentOutput output, Instant now) {
		// A request to stop texting is honoured whatever else is going on: it's the law.
		if (output.unsubscribe()) {
			optOuts.optOut(conversation);
			return Optional.empty();
		}
		if (output.abusive()) {
			conversation.end(EndReason.ABUSIVE);
			return Optional.empty();
		}
		if (output.objection() && conversation.recordObjection() >= OBJECTIONS_BEFORE_GIVING_UP) {
			conversation.decline();
			// A fixed goodbye, not the model's wording: the last message must not invite a reply.
			return Optional.of(Message.fromTemplate(DECLINED_TEMPLATE, declinedMessage(conversation), now));
		}
		if (output.reply() == null || output.reply().isBlank()) {
			return Optional.empty();
		}
		return Optional.of(Message.fromModel(output.reply().strip(), now));
	}

	private String declinedMessage(Conversation conversation) {
		String template = assets.text(conversation.getAgentVersion(), conversation.getLocale(),
				"sms/" + DECLINED_TEMPLATE + ".txt");
		return Templates.render(template, Map.of());
	}

}
