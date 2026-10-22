package com.enrola.salesagent.agent;

import java.time.Clock;
import java.util.Locale;

import com.enrola.salesagent.assets.AgentAssets;
import com.enrola.salesagent.config.AgentProperties;

/** Lets tests in other packages build a PromptBuilder without Spring. */
public final class PromptBuilderTestAccess {

	private PromptBuilderTestAccess() {
	}

	public static PromptBuilder promptBuilder(String agentVersion, Clock clock) {
		return new PromptBuilder(new AgentAssets(new AgentProperties(agentVersion, Locale.forLanguageTag("en-AU"))), clock);
	}

}
