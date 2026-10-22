package com.enrola.salesagent.conversation;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** A phone number that has opted out. Nothing is ever sent to it again. */
@Entity
public class OptOut {

	/** E.164, so every lead for the same person matches. */
	@Id
	private String phone;

	private Long conversationId;

	@Column(nullable = false)
	private Instant createdAt;

	protected OptOut() {
		// for JPA
	}

	OptOut(String phone, Long conversationId, Instant createdAt) {
		this.phone = phone;
		this.conversationId = conversationId;
		this.createdAt = createdAt;
	}

	public String getPhone() {
		return phone;
	}

	public Long getConversationId() {
		return conversationId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
