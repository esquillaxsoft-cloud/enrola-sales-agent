package com.enrola.salesagent.agent;

import java.util.List;

/**
 * Screens a lead's SMS for abuse before the model sees it.
 *
 * <p>Only abuse aimed at us (harassment, hate, threats) counts. Self-harm is deliberately not
 * "abuse": a lead disclosing distress after a health event must not be cut off; the model
 * answers them, kindly. A moderation failure is never fatal: the model's own {@code abusive}
 * flag is the second layer.
 */
public interface Moderator {

	/** @return the abuse categories flagged, or empty when the message is fine (or unchecked) */
	List<String> abusiveCategories(String message);

	Moderator NONE = message -> List.of();

}
