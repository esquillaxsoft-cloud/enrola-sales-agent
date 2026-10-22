package com.enrola.salesagent.agent;

/**
 * The model couldn't produce a usable turn (network, rate limit, refusal, malformed output). The
 * lead's message is already stored; retrying the same inbound SMS resumes the turn.
 */
public class AgentUnavailableException extends RuntimeException {

	public AgentUnavailableException(String message) {
		super(message);
	}

	public AgentUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

}
