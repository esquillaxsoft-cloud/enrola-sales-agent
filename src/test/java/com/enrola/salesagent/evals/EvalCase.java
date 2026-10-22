package com.enrola.salesagent.evals;

import java.util.List;
import java.util.Map;

/**
 * One eval case, read from {@code src/test/resources/evals/cases/*.json}: the lead, the turns after
 * the opener, the lead's next SMS, and what the agent's single reply should do.
 */
record EvalCase(String id, String about, String lead, List<Turn> history, String message, Expect expect) {

	record Turn(String from, String body) {
	}

	/**
	 * @param reply            "present", "absent", or null for either
	 * @param flags            expected objection / unsubscribe / abusive; unlisted flags must be false
	 * @param replyMatches     regexes that must all match the reply
	 * @param replyNotMatches  regexes none of which may match the reply
	 * @param endsWithQuestion the reply must end with "?"
	 * @param stageIn          the stage must be one of these, if given
	 */
	record Expect(String reply, Map<String, Boolean> flags, List<String> replyMatches, List<String> replyNotMatches,
			Boolean endsWithQuestion, List<String> stageIn) {
	}

	/** What the model sees, without the expectations: changing a check must not invalidate the cache. */
	String inputKey() {
		StringBuilder key = new StringBuilder(lead).append('\n');
		if (history != null) {
			history.forEach(turn -> key.append(turn.from()).append(": ").append(turn.body()).append('\n'));
		}
		return key.append("lead: ").append(message).toString();
	}

}
