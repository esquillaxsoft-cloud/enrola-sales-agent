package com.enrola.salesagent.agent;

import com.enrola.salesagent.conversation.Conversation;

/**
 * Produces the agent's next message from the conversation so far, including the lead message
 * that just arrived. The OpenAI implementation replaces the stub in commit 11.
 */
public interface AgentReplier {

	AgentReply reply(Conversation conversation);

}
