package com.enrola.salesagent.lead;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;

/**
 * Normalises phone numbers to E.164 (e.g. +61457099876) so one person is one number, whatever
 * format a lead arrives in. Duplicate detection and per-number opt-out depend on this.
 */
public final class PhoneNumbers {

	/** Comparato's leads are Australian, so numbers without a country code are read as AU. */
	private static final String DEFAULT_REGION = "AU";

	private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();

	private PhoneNumbers() {
	}

	/** @throws IllegalArgumentException if the input isn't a valid phone number */
	public static String toE164(String raw) {
		try {
			var number = UTIL.parse(raw, DEFAULT_REGION);
			if (!UTIL.isValidNumber(number)) {
				throw new IllegalArgumentException("Not a valid phone number: " + raw);
			}
			return UTIL.format(number, PhoneNumberFormat.E164);
		}
		catch (NumberParseException e) {
			throw new IllegalArgumentException("Not a phone number: " + raw, e);
		}
	}

}
