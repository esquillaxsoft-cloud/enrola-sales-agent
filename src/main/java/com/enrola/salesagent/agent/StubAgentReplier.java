package com.enrola.salesagent.agent;

import java.util.List;
import java.util.Locale;

import com.enrola.salesagent.agent.AgentOutput.Stage;
import com.enrola.salesagent.conversation.Conversation;

/**
 * A canned agent, so the whole loop, including objection handling, can be tried in the UI before
 * any model is wired in. It spots a few obvious objections by phrase; the model will do this
 * properly. Used when no OpenAI key is configured (see AgentConfig).
 */
class StubAgentReplier implements AgentReplier {

	private static final List<String> OBJECTIONS = List.of("not interested", "no thanks", "too busy");

	@Override
	public AgentOutput reply(Conversation conversation) {
		String latest = conversation.getMessages().getLast().getBody().toLowerCase(Locale.ROOT);
		if (OBJECTIONS.stream().anyMatch(latest::contains)) {
			return new AgentOutput("Are you sure I can't change your mind? (Stub agent: no model yet.)", Stage.SUGGEST_CALL,
					true, false, false, "stub: objection phrase");
		}
		String name = conversation.getLead().getGivenName();
		return AgentOutput.reply("Thanks " + name + ", got that. (Stub agent: no model yet.)", Stage.ENGAGE, "stub");
	}

}
