package com.enrola.salesagent.assets;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills {@code {{name}}} placeholders. Named rather than positional so a translator can see what
 * each one means, and with no escape characters, so copy like "it's" and "Reply 'stop'" is
 * safe (unlike MessageFormat, where a lone apostrophe swallows text).
 *
 * <p>Fails loudly when a template uses a variable the caller didn't supply, or supplied as null:
 * an SMS must never go out as "Hi {{givenName}}" or "Hi null".
 */
public final class Templates {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9]*)\\s*}}");

	private Templates() {
	}

	public static String render(String template, Map<String, String> variables) {
		Matcher matcher = PLACEHOLDER.matcher(template);
		StringBuilder out = new StringBuilder();
		while (matcher.find()) {
			String name = matcher.group(1);
			String value = variables.get(name);
			if (value == null) {
				throw new IllegalArgumentException("Template needs {{" + name + "}} but it wasn't supplied");
			}
			matcher.appendReplacement(out, Matcher.quoteReplacement(value));
		}
		matcher.appendTail(out);
		return out.toString();
	}

}
