package com.enrola.salesagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SmsTextTest {

	@Test
	void typographicPunctuationBecomesPlain() {
		assertThat(SmsText.plain("You’re in – it’s “free”… really free"))
				.isEqualTo("You're in - it's \"free\"... really free")
				.isASCII();
	}

	@Test
	void plainTextIsUntouched() {
		assertThat(SmsText.plain("Fair enough - hospital, extras or both?")).isEqualTo("Fair enough - hospital, extras or both?");
	}

	@Test
	void theLimitIs320Characters() {
		assertThat(SmsText.tooLong("a".repeat(320))).isFalse();
		assertThat(SmsText.tooLong("a".repeat(321))).isTrue();
		assertThat(SmsText.tooLong(null)).isFalse();
	}

}
