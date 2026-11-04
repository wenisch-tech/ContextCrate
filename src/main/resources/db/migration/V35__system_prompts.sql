-- Installation-wide and per-crate system prompts for the answer model.
-- Crate prompts are applied only while the administrator allows them (off by default).
alter table system_setting add column allow_crate_system_prompts boolean not null default false;
alter table system_setting add column global_system_prompt text;
alter table crate_rag_settings add column system_prompt text;
