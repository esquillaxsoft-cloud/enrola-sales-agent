package com.enrola.salesagent.assets;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.enrola.salesagent.config.AgentProperties;

/**
 * Reads the agent's text assets (prompt, info pack, SMS templates) from
 * {@code agents/<version>/<name>_<locale>.<ext>} on the classpath.
 *
 * <p>Lookup uses the JDK's standard locale fallback (en_AU, then en, then the unsuffixed file),
 * then the same chain for the default locale. So a conversation in a language we have no copy
 * for gets English rather than an error. Only en_AU files exist today.
 */
@Component
public class AgentAssets {

	private static final ResourceBundle.Control CONTROL = ResourceBundle.Control.getNoFallbackControl(
			ResourceBundle.Control.FORMAT_DEFAULT);

	private final Locale defaultLocale;

	public AgentAssets(AgentProperties agent) {
		this.defaultLocale = agent.defaultLocale();
	}

	/**
	 * @param agentVersion e.g. "comparato/v1"
	 * @param name         path within the bundle, with extension, e.g. "sms/opener-no-provider.txt"
	 * @throws IllegalStateException if no locale variant exists
	 */
	public String text(String agentVersion, Locale locale, String name) {
		List<String> tried = new ArrayList<>();
		for (String path : candidatePaths(agentVersion, locale, name)) {
			Resource resource = new ClassPathResource(path);
			if (resource.exists()) {
				return read(resource);
			}
			tried.add(path);
		}
		throw new IllegalStateException("No asset " + name + " for " + agentVersion + "; tried " + tried);
	}

	List<String> candidatePaths(String agentVersion, Locale locale, String name) {
		int dot = name.lastIndexOf('.');
		String base = dot < 0 ? name : name.substring(0, dot);
		String extension = dot < 0 ? "" : name.substring(dot);

		// Requested chain without its root, then the default chain, so the unsuffixed file comes last:
		// fr_FR -> fr -> en_AU -> en -> (unsuffixed).
		Set<Locale> locales = new LinkedHashSet<>(CONTROL.getCandidateLocales(base, locale));
		locales.remove(Locale.ROOT);
		locales.addAll(CONTROL.getCandidateLocales(base, defaultLocale));

		return locales.stream()
				.map(candidate -> candidate.equals(Locale.ROOT) ? "" : "_" + candidate)
				.map(suffix -> "agents/" + agentVersion + "/" + base + suffix + extension)
				.toList();
	}

	static String read(Resource resource) {
		try (var in = resource.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8)
					// Git may check these files out with CRLF on Windows; an SMS must be identical on every OS.
					.replace("\r\n", "\n")
					// Files end with a newline; an SMS shouldn't.
					.strip();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
