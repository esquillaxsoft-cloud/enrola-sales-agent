package com.enrola.salesagent.agent;

/**
 * Rules every SMS the model writes must meet before it's sent.
 *
 * <p>Plain punctuation matters for cost, not just style: one character outside the basic SMS
 * alphabet (a curly apostrophe, an en dash) switches the whole message to an encoding with 70
 * characters per message instead of 160, so a 320-character reply costs 5 SMS instead of 3.
 */
public final class SmsText {

	/** The brief: "Responses need to be suitable for SMS (max 320 characters)". */
	public static final int MAX_LENGTH = 320;

	private SmsText() {
	}

	/** Typographic punctuation the model likes to produce, swapped for plain equivalents. */
	public static String plain(String text) {
		return text
				.replace('‘', '\'').replace('’', '\'')    // ‘ ’
				.replace('“', '"').replace('”', '"')      // “ ”
				.replace('–', '-').replace('—', '-')      // – —
				.replace("…", "...")                            // …
				.replace(' ', ' ');                             // non-breaking space
	}

	public static boolean tooLong(String text) {
		return text != null && plain(text).strip().length() > MAX_LENGTH;
	}

}
