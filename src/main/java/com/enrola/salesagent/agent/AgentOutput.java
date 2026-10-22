package com.enrola.salesagent.agent;

/**
 * What the model returns every turn (OpenAI structured output, schema in
 * {@code agent-output.schema.json}). The model <em>reports</em>; code decides what happens:
 * {@link ConversationPolicy} turns these flags into state changes and decides whether the reply
 * is sent.
 *
 * <p>There's deliberately no "goal met" flag. A call is booked only when the booking tool
 * succeeds, so the model can't claim a booking that doesn't exist.
 *
 * @param reply       the SMS to send, or null to stay silent (e.g. "thanks!" after booking)
 * @param stage       where the conversation is in the designer's script; for the UI and analysis
 * @param objection   this message pushes back on the call ("too busy", "not interested")
 * @param unsubscribe the lead asked not to be contacted, in words a keyword can't catch
 * @param abusive     the lead is abusive; the conversation ends without a reply
 * @param note        one line for the humans reviewing this turn: why the agent did what it did
 */
public record AgentOutput(
		String reply,
		Stage stage,
		boolean objection,
		boolean unsubscribe,
		boolean abusive,
		String note) {

	/** The designer's five steps, plus what comes after. */
	public enum Stage {
		ENGAGE,
		PREFERENCE,
		SUGGEST_CALL,
		OFFER_TIMES,
		BOOKED,
		AFTER_BOOKING,
		CLOSING
	}

	public static AgentOutput reply(String reply, Stage stage, String note) {
		return new AgentOutput(reply, stage, false, false, false, note);
	}

}
