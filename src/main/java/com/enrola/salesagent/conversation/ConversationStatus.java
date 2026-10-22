package com.enrola.salesagent.conversation;

/**
 * Where a conversation stands. BOOKED is not terminal: a booked lead can still reschedule,
 * cancel or ask a question. The other non-ACTIVE statuses are terminal and the agent stops
 * replying.
 */
public enum ConversationStatus {

	ACTIVE(false),
	BOOKED(false),
	OPTED_OUT(true),
	DECLINED(true),
	/** Ended for a reason in {@link EndReason}. */
	ENDED(true);

	private final boolean terminal;

	ConversationStatus(boolean terminal) {
		this.terminal = terminal;
	}

	public boolean isTerminal() {
		return terminal;
	}

}
