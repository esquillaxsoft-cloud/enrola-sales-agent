package com.enrola.salesagent.conversation;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

/** One SMS, in or out. */
@Entity
public class Message {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "conversation_id")
	private Conversation conversation;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Sender sender;

	@Column(nullable = false)
	private String body;

	/** Set when code rendered the message from a template, e.g. "opener-known-provider". */
	private String templateKey;

	/**
	 * On a lead message: the model's structured output (JSON) for the turn this message started,
	 * kept even when the agent decided not to reply.
	 */
	private String agentOutput;

	/** The platform's id for an inbound SMS, used to ignore webhook retries. */
	private String platformMessageId;

	@Column(nullable = false)
	private Instant createdAt;

	protected Message() {
		// for JPA
	}

	private Message(Sender sender, String body, String templateKey, Instant createdAt) {
		this.sender = sender;
		this.body = body;
		this.templateKey = templateKey;
		this.createdAt = createdAt;
	}

	public static Message fromLead(String body, Instant at) {
		return fromLead(null, body, at);
	}

	/** @param platformMessageId the platform's id for this SMS, or null when there isn't one */
	public static Message fromLead(String platformMessageId, String body, Instant at) {
		Message message = new Message(Sender.LEAD, body, null, at);
		message.platformMessageId = platformMessageId;
		return message;
	}

	public static Message fromTemplate(String templateKey, String body, Instant at) {
		return new Message(Sender.AGENT, body, templateKey, at);
	}

	/** An SMS the model wrote. Its reasoning is on the lead message that started the turn. */
	public static Message fromModel(String body, Instant at) {
		return new Message(Sender.AGENT, body, null, at);
	}

	/** Records the model's structured output for the turn this lead message started. */
	public void recordAgentOutput(String json) {
		if (sender != Sender.LEAD) {
			throw new IllegalStateException("Agent output belongs on the lead message that started the turn");
		}
		this.agentOutput = json;
	}

	void attachTo(Conversation conversation) {
		this.conversation = conversation;
	}

	public Long getId() {
		return id;
	}

	public Conversation getConversation() {
		return conversation;
	}

	public Sender getSender() {
		return sender;
	}

	public String getBody() {
		return body;
	}

	public String getTemplateKey() {
		return templateKey;
	}

	public String getAgentOutput() {
		return agentOutput;
	}

	public String getPlatformMessageId() {
		return platformMessageId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
