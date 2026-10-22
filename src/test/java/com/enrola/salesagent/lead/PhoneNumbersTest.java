package com.enrola.salesagent.lead;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumbersTest {

	@ParameterizedTest
	@ValueSource(strings = { "+61457099876", "0457 099 876", "0457099876", "+61 457 099 876", "61457099876",
			"(04) 5709-9876" })
	void sameMobileInAnyFormatNormalisesToOneNumber(String raw) {
		assertThat(PhoneNumbers.toE164(raw)).isEqualTo("+61457099876");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "not a number", "123", "0457 099" })
	void rejectsThingsThatArentPhoneNumbers(String raw) {
		assertThatThrownBy(() -> PhoneNumbers.toE164(raw)).isInstanceOf(IllegalArgumentException.class);
	}

}
