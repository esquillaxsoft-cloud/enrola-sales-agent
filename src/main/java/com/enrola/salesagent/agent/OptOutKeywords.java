package com.enrola.salesagent.agent;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.enrola.salesagent.assets.AgentAssets;
import com.enrola.salesagent.conversation.Conversation;

/**
 * Recognises an explicit opt-out ("STOP", "Unsubscribe.", "opt-out") without the model. The
 * keyword list is a per-locale asset, since STOP is an English word.
 */
@Component
public class OptOutKeywords {

	private final AgentAssets assets;

	OptOutKeywords(AgentAssets assets) {
		this.assets = assets;
	}

	/** True when the whole message is an opt-out keyword; "can you stop by Tuesday?" is not. */
	public boolean isOptOut(Conversation conversation, String message) {
		return keywords(conversation).contains(normalise(message));
	}

	private Set<String> keywords(Conversation conversation) {
		String file = assets.text(conversation.getAgentVersion(), conversation.getLocale(), "opt-out-keywords.txt");
		return file.lines()
				.map(String::strip)
				.filter(line -> !line.isEmpty() && !line.startsWith("#"))
				.map(OptOutKeywords::normalise)
				.collect(Collectors.toSet());
	}

	/** Lower case, punctuation to spaces, single spaces: "Opt-out!" becomes "opt out". */
	static String normalise(String text) {
		return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
	}

}
