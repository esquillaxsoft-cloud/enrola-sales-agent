package com.enrola.salesagent.evals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import com.enrola.salesagent.agent.AgentTurn;

import tools.jackson.databind.json.JsonMapper;

/**
 * Model outputs on disk, keyed by model + prompt-bundle hash + case input. Re-running with nothing
 * changed costs nothing; editing the prompt changes the bundle hash, so only then are cases paid
 * for again. Verdicts aren't cached, only outputs, so changing a check never costs a token.
 */
final class EvalCache {

	private final Path dir;
	private final JsonMapper json;

	EvalCache(Path dir, JsonMapper json) {
		this.dir = dir;
		this.json = json;
	}

	static String key(String model, String bundleHash, EvalCase evalCase) {
		return sha256(model + "\n" + bundleHash + "\n" + evalCase.inputKey()).substring(0, 32);
	}

	Optional<AgentTurn> get(String key) {
		Path file = dir.resolve(key + ".json");
		if (!Files.exists(file)) {
			return Optional.empty();
		}
		try {
			return Optional.of(json.readValue(Files.readString(file), AgentTurn.class));
		}
		catch (IOException e) {
			return Optional.empty();
		}
	}

	void put(String key, AgentTurn turn) {
		try {
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(key + ".json"), json.writeValueAsString(turn));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static String sha256(String text) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
