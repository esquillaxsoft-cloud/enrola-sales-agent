package com.enrola.salesagent.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TemplatesTest {

	@Test
	void fillsNamedPlaceholders() {
		assertThat(Templates.render("Hi {{givenName}}, you're with {{ currentProvider }}.",
				Map.of("givenName", "John", "currentProvider", "HBF")))
				.isEqualTo("Hi John, you're with HBF.");
	}

	@Test
	void apostrophesAndQuotesSurvive() {
		// MessageFormat would treat these single quotes as escapes and drop text.
		assertThat(Templates.render("Hi {{givenName}}, it's Anna. Reply 'stop' to opt out", Map.of("givenName", "Jane")))
				.isEqualTo("Hi Jane, it's Anna. Reply 'stop' to opt out");
	}

	@Test
	void valuesAreInsertedLiterally() {
		assertThat(Templates.render("Premium {{premium}}", Map.of("premium", "$350-$450")))
				.isEqualTo("Premium $350-$450");
	}

	@Test
	void missingVariableFailsLoudly() {
		assertThatThrownBy(() -> Templates.render("Hi {{givenName}}", Map.of()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("givenName");
	}

	@Test
	void nullVariableFailsLoudlyRatherThanSendingNull() {
		Map<String, String> variables = new HashMap<>();
		variables.put("currentProvider", null);

		assertThatThrownBy(() -> Templates.render("{{currentProvider}} members", variables))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
