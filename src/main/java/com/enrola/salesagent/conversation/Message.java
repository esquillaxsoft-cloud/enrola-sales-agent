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

	/** The model's structured output (JSON) for the turn that produced this message. */
	private String agentOutput;

	@Column(nullable = false)
	private Instant createdAt;

	protected Message() {
		// for JPA
	}

	private Message(Sender sender, String body, String templateKey, String agentOutput, Instant createdAt) {
		this.sender = sender;
		this.body = body;
		this.templateKey = templateKey;
		this.agentOutput = agentOutput;
		this.createdAt = createdAt;
	}

	public static Message fromLead(String body, Instant at) {
		return new Message(Sender.LEAD, body, null, null, at);
	}

	public static Message fromTemplate(String templateKey, String body, Instant at) {
		return new Message(Sender.AGENT, body, templateKey, null, at);
	}

	public static Message fromModel(String body, String agentOutput, Instant at) {
		return new Message(Sender.AGENT, body, null, agentOutput, at);
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

	public Instant getCreatedAt() {
		return createdAt;
	}

}
