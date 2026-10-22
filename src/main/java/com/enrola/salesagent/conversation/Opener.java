package com.enrola.salesagent.conversation;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.enrola.salesagent.assets.AgentAssets;
import com.enrola.salesagent.assets.Templates;
import com.enrola.salesagent.lead.Lead;

/**
 * The first SMS every lead gets. A template, not the model: it goes to every lead, carries the
 * opt-out text, and Comparato will want to approve it word for word.
 */
@Component
public class Opener {

	static final String KNOWN_PROVIDER = "opener-known-provider";
	static final String NO_PROVIDER = "opener-no-provider";

	private final AgentAssets assets;

	Opener(AgentAssets assets) {
		this.assets = assets;
	}

	public Message compose(Conversation conversation, Instant at) {
		Lead lead = conversation.getLead();
		String key = lead.getCurrentProvider() == null ? NO_PROVIDER : KNOWN_PROVIDER;

		Map<String, String> variables = new HashMap<>();
		variables.put("givenName", lead.getGivenName());
		variables.put("currentProvider", lead.getCurrentProvider());

		String template = assets.text(conversation.getAgentVersion(), conversation.getLocale(), "sms/" + key + ".txt");
		return Message.fromTemplate(key, Templates.render(template, variables), at);
	}

}
