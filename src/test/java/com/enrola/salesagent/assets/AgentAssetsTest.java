package com.enrola.salesagent.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import com.enrola.salesagent.config.AgentProperties;

/** Uses the test bundle in src/test/resources/agents/test/v1: greeting_en_AU, greeting_en, farewell. */
class AgentAssetsTest {

	static final Locale EN_AU = Locale.forLanguageTag("en-AU");

	AgentAssets assets = new AgentAssets(new AgentProperties("test/v1", EN_AU));

	@Test
	void exactLocaleWins() {
		assertThat(assets.text("test/v1", EN_AU, "greeting.txt")).isEqualTo("G'day");
	}

	@Test
	void fallsBackToTheLanguage() {
		assertThat(assets.text("test/v1", Locale.UK, "greeting.txt")).isEqualTo("Hello");
	}

	@Test
	void anUnsupportedLanguageGetsTheDefaultLocaleRatherThanAnError() {
		assertThat(assets.text("test/v1", Locale.FRANCE, "greeting.txt")).isEqualTo("G'day");
	}

	@Test
	void theUnsuffixedFileIsTheLastResort() {
		assertThat(assets.text("test/v1", Locale.FRANCE, "farewell.txt")).isEqualTo("Bye (unsuffixed)");
		assertThat(assets.candidatePaths("test/v1", Locale.FRANCE, "farewell.txt")).containsExactly(
				"agents/test/v1/farewell_fr_FR.txt",
				"agents/test/v1/farewell_fr.txt",
				"agents/test/v1/farewell_en_AU.txt",
				"agents/test/v1/farewell_en.txt",
				"agents/test/v1/farewell.txt");
	}

	@Test
	void windowsLineEndingsAreNormalisedSoTheSmsIsTheSameOnEveryOs() {
		var crlf = new ByteArrayResource("Hi {{givenName}}.\r\n\r\nReply 'stop' to opt out\r\n".getBytes(StandardCharsets.UTF_8));

		assertThat(AgentAssets.read(crlf)).isEqualTo("Hi {{givenName}}.\n\nReply 'stop' to opt out");
	}

	@Test
	void missingAssetSaysWhereItLooked() {
		assertThatThrownBy(() -> assets.text("test/v1", EN_AU, "nope.txt"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("agents/test/v1/nope_en_AU.txt");
	}

}
