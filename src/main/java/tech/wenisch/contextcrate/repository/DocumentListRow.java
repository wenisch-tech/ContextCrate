package tech.wenisch.contextcrate.repository;

import tech.wenisch.contextcrate.domain.NormalizedDocument;

public record DocumentListRow(NormalizedDocument document, long chunkCount) {}
