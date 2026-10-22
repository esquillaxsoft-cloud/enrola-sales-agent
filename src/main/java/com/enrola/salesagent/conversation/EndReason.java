package com.enrola.salesagent.conversation;

/** Why a conversation is {@link ConversationStatus#ENDED}. */
public enum EndReason {
	/** The lead was abusive; the agent stops without replying. */
	ABUSIVE,
	/** The agent hit its per-conversation message cap; a backstop against reply loops. */
	MESSAGE_CAP
}
