package com.enrola.salesagent.agent;

/**
 * What the agent decided for one turn.
 *
 * @param body   the SMS to send
 * @param output the structured output behind it, as JSON, stored on the message for the UI and
 *               for evaluation. Its shape is defined with the model contract (commit 10).
 */
public record AgentReply(String body, String output) {
}
