package com.enrola.salesagent.config;

import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Which agent bundle new conversations use, and the agent's hard limits. Existing conversations
 * keep the version and locale they started with.
 *
 * @param version          prompt and template bundle, e.g. "comparato/v1"
 * @param defaultLocale    language for new conversations; only en_AU assets exist
 * @param maxAgentMessages most SMS the agent sends in one conversation, opener included. A
 *                         backstop against reply loops: a good booking takes about six.
 */
@ConfigurationProperties("agent")
public record AgentProperties(String version, Locale defaultLocale, @DefaultValue("20") int maxAgentMessages) {

	@ConstructorBinding
	public AgentProperties {
	}

	/** For tests that don't care about limits. */
	public AgentProperties(String version, Locale defaultLocale) {
		this(version, defaultLocale, 20);
	}

}
