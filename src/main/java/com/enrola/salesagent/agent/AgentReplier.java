package com.enrola.salesagent.agent;

import com.enrola.salesagent.conversation.Conversation;

/**
 * Reads the conversation so far, including the lead message that just arrived, and reports on
 * the turn: the reply it would send and what it noticed. The OpenAI implementation replaces the
 * stub in commit 11.
 */
public interface AgentReplier {

	AgentOutput reply(Conversation conversation);

}
