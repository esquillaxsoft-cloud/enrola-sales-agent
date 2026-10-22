package com.enrola.salesagent.config;

import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which agent bundle new conversations use. Existing conversations keep the version and locale
 * they started with.
 *
 * @param version       prompt and template bundle, e.g. "comparato/v1"
 * @param defaultLocale language for new conversations; only en_AU assets exist
 */
@ConfigurationProperties("agent")
public record AgentProperties(String version, Locale defaultLocale) {
}
