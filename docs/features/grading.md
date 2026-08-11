# Grading

Grading is enabled by default for every crate. Before ContextCrate generates an answer, it sends
each retrieved chunk and the question to the configured answer model and asks whether the chunk is
relevant. Only chunks answered with exactly `yes` become answer context and appear in the answer
stream's `sources` event or the dashboard source list.

Use the **Grade retrieved chunks with the answer model** switch in the crate's RAG settings to turn
the feature off. With grading off, retrieved chunks are passed to answer generation unchanged.

## Model calls and privacy

Grading uses the crate's existing OpenAI-compatible answer endpoint, model, API key, headers, and
timeout. It makes one non-streaming chat-completion request per retrieved chunk before the normal
streaming answer request. This can improve context quality, but increases latency and LLM usage.

The question and chunk contents are sent to that endpoint as untrusted data. They are not written
to audit logs. Use TLS and treat the provider as a data processor.

## Fallback behavior

An exact `no` excludes a chunk. An exact `yes` retains it. If the grader is unavailable, fails, or
returns any other response, ContextCrate retains the affected chunk and continues answer generation.
This fail-open behavior keeps answers available when grading cannot be completed. If every chunk is
explicitly rejected, normal strict-grounding behavior applies.
