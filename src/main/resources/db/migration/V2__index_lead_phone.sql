-- Phone numbers are stored in E.164, so this finds every lead for one person: used to keep one
-- open conversation per number and to apply opt-outs per number. Not unique: duplicates are
-- expected and kept as history.
create index lead_phone_idx on lead (phone);
