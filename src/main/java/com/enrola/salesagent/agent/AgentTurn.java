package com.enrola.salesagent.agent;

import java.util.List;

/**
 * Everything one turn of the agent produced: the model's report, the tools it ran, and what it
 * cost. Recorded on the lead's message, for the UI's turn panel now and for evals later.
 *
 * @param output    the model's structured output
 * @param toolCalls the tools it called, in order, with their results
 * @param usage     tokens, rounds and time across every model call in the turn; null for the stub
 */
public record AgentTurn(AgentOutput output, List<ToolCall> toolCalls, Usage usage) {

	public record ToolCall(String name, String arguments, String result) {
	}

	/**
	 * @param rounds       model calls in the turn (one, plus one per round of tool calls)
	 * @param cachedTokens input tokens served from OpenAI's prompt cache, which are cheaper
	 */
	public record Usage(String model, int rounds, long inputTokens, long cachedTokens, long outputTokens,
			long latencyMillis) {

		/** For a turn that took two attempts (a retry), the cost of both. */
		public Usage plus(Usage other) {
			if (other == null) {
				return this;
			}
			return new Usage(other.model, rounds + other.rounds, inputTokens + other.inputTokens,
					cachedTokens + other.cachedTokens, outputTokens + other.outputTokens,
					latencyMillis + other.latencyMillis);
		}

	}

	/** A turn with no tools and no usage, e.g. from the stub. */
	public static AgentTurn of(AgentOutput output) {
		return new AgentTurn(output, List.of(), null);
	}

}
