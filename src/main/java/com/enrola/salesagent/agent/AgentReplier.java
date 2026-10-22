package com.enrola.salesagent.agent;

import com.enrola.salesagent.conversation.Conversation;

/**
 * Reads the conversation so far, including the lead message that just arrived, and reports on
 * the turn: the reply it would send, what it noticed, the tools it ran and what that cost.
 */
public interface AgentReplier {

	AgentTurn reply(Conversation conversation);

	/**
	 * Asks again with a note on what was wrong with the last attempt (e.g. too long for SMS).
	 * Implementations that can't take the note just answer again.
	 */
	default AgentTurn reply(Conversation conversation, String correction) {
		return reply(conversation);
	}

}
