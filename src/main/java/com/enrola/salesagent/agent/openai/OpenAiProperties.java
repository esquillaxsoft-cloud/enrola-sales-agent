package com.enrola.salesagent.agent.openai;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param apiKey          from OPENAI_API_KEY; when blank the app runs with the stub agent
 * @param model           a full-size model ("mini" models aren't good enough here, per the CTO)
 * @param baseUrl         the API root
 * @param timeout         how long to wait for one response
 * @param maxOutputTokens cap per turn; an SMS plus flags needs a few hundred at most
 * @param moderationModel model for the (free) moderation endpoint
 */
@ConfigurationProperties("openai")
public record OpenAiProperties(String apiKey, String model, String baseUrl, Duration timeout, int maxOutputTokens,
		String moderationModel) {

	static final String DEFAULT_MODEL = "gpt-6.1-sol";

	public OpenAiProperties {
		// .env.example leaves OPENAI_MODEL empty, which arrives as "" rather than missing.
		model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
		baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com/v1" : baseUrl;
		timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
		maxOutputTokens = maxOutputTokens <= 0 ? 600 : maxOutputTokens;
		moderationModel = moderationModel == null || moderationModel.isBlank() ? "omni-moderation-latest" : moderationModel;
	}

	public boolean hasKey() {
		return apiKey != null && !apiKey.isBlank();
	}

}
