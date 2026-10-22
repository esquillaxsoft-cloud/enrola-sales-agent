-- The platform's id for an inbound SMS. Webhooks get retried (e.g. after a timeout), so a repeat
-- id returns the original reply instead of running the agent again and texting the lead twice.
-- Null for messages typed into the prototype UI; nulls don't collide in a unique index.
alter table message add column platform_message_id varchar(64);

create unique index message_platform_id_uq on message (conversation_id, platform_message_id);
