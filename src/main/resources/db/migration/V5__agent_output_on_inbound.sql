-- The model's structured output now lives on the lead's inbound message: the turn it decided.
-- On the agent's reply it was lost whenever the agent stayed silent (abuse, a soft opt-out,
-- "thanks!" after booking), which are exactly the turns you most want to explain.
alter table message drop constraint message_lead_has_no_agent_fields;

alter table message add constraint message_template_only_on_agent
    check (sender = 'AGENT' or template_key is null);
