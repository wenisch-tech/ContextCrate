# Answer Verification

Answer Verification is enabled by default for every crate. After an answer is generated,
ContextCrate sends the answer and the retrieved source chunks to the configured answer model. The
model must reply `yes` only when every factual statement is supported by those chunks.

Verification buffers the generated answer until the check finishes, so answers no longer arrive
token-by-token. Retrieved sources are still sent before the final answer. Configure the feature in
the crate's RAG settings and choose one action when unsupported claims are found:

- **Revise once** (default) asks the model to rewrite the answer using only supported source facts.
- **Block answer** returns the standard knowledge-base no-answer response.
- **Return with warning** returns the original answer with an unsupported-claims warning.

If verification fails or produces any response other than `yes` or `no`, ContextCrate returns the
original answer with a not-verified warning. The answer stream emits a `verification` event with
`verified`, `revised`, `blocked`, `unsupported`, or `unavailable` status before answer text.

## Privacy and limitations

Verification uses the configured OpenAI-compatible answer endpoint, model, credentials, headers,
and timeout. It sends retrieved chunk content and generated answer text to that provider, adds at
least one LLM request and latency, and is not a guarantee of factual correctness. Prompts, chunks,
answers, and verification results are not written to audit logs. Use TLS and treat the provider as
a data processor.
