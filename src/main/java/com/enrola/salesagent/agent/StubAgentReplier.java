package com.enrola.salesagent.agent;

import org.springframework.stereotype.Component;

import com.enrola.salesagent.conversation.Conversation;

/** A canned reply, so the whole loop can be exercised before any model is wired in. */
@Component
class StubAgentReplier implements AgentReplier {

	@Override
	public AgentReply reply(Conversation conversation) {
		String name = conversation.getLead().getGivenName();
		return new AgentReply("Thanks " + name + ", got that. (Stub agent: no model yet.)", "{\"stub\":true}");
	}

}
