-- Suppression list: phone numbers that have opted out. Keyed by number, not lead or
-- conversation, because one person can arrive under several lead ids, and a compliance record
-- must outlive the conversation it came from. No foreign key to conversation for the same reason.
create table opt_out (
    phone           varchar(20) primary key,  -- E.164
    conversation_id bigint,                   -- where the opt-out happened, for the record
    created_at      timestamp with time zone not null
);
