package com.enrola.salesagent.evals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.enrola.salesagent.agent.AgentOutput;
import com.enrola.salesagent.agent.SmsText;

/**
 * The checks, in code: one behaviour each, so a failure says exactly what went wrong. No model
 * judges another. Run on every result, cached or fresh, so changing a check costs nothing.
 */
final class Checks {

	record Check(String name, boolean passed, String detail) {
	}

	static final List<String> FLAGS = List.of("objection", "unsubscribe", "abusive");

	private Checks() {
	}

	static List<Check> judge(EvalCase evalCase, AgentOutput output) {
		EvalCase.Expect expect = evalCase.expect();
		String reply = output.reply() == null || output.reply().isBlank() ? null : output.reply().strip();
		List<Check> checks = new ArrayList<>();

		if ("present".equals(expect.reply())) {
			checks.add(check("replies", reply != null, "no reply"));
		}
		if ("absent".equals(expect.reply())) {
			checks.add(check("stays silent", reply == null, reply));
		}

		// Every flag is checked: one set when it shouldn't be is as wrong as one missed.
		Map<String, Boolean> flags = expect.flags() == null ? Map.of() : expect.flags();
		for (String flag : FLAGS) {
			boolean expected = flags.getOrDefault(flag, false);
			boolean actual = switch (flag) {
				case "objection" -> output.objection();
				case "unsubscribe" -> output.unsubscribe();
				default -> output.abusive();
			};
			checks.add(check(flag + " = " + expected, actual == expected, flag + " was " + actual));
		}

		if (reply != null) {
			// The SMS rules, on every reply.
			checks.add(check("<= " + SmsText.MAX_LENGTH + " chars", !SmsText.tooLong(reply), reply.length() + " chars"));
			checks.add(check("no '!'", !reply.contains("!"), reply));
			for (String regex : nullSafe(expect.replyMatches())) {
				checks.add(check("matches " + regex, Pattern.compile(regex).matcher(reply).find(), reply));
			}
			for (String regex : nullSafe(expect.replyNotMatches())) {
				checks.add(check("doesn't match " + regex, !Pattern.compile(regex).matcher(reply).find(), reply));
			}
			if (Boolean.TRUE.equals(expect.endsWithQuestion())) {
				checks.add(check("ends with a question", reply.endsWith("?"), reply));
			}
		}
		if (expect.stageIn() != null) {
			String stage = output.stage() == null ? null : output.stage().name();
			checks.add(check("stage in " + expect.stageIn(), expect.stageIn().contains(stage), "stage " + stage));
		}
		return checks;
	}

	private static Check check(String name, boolean passed, String detail) {
		return new Check(name, passed, passed ? null : detail);
	}

	private static List<String> nullSafe(List<String> values) {
		return values == null ? List.of() : values;
	}

}
