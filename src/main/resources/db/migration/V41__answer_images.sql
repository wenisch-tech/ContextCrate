-- Retrieved image sources are attached to the answer request (OpenAI image_url parts); on by default.
alter table crate_rag_settings add column send_images_to_model boolean not null default true;
