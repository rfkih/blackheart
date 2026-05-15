-- V76 — Enforce content-addressed uniqueness on model_registry.
--
-- M5e's POST /models/register treats artifact_sha256 as the
-- content-addressed identity of an artifact: re-posting the same sha
-- must return the same model_id (idempotent on content). The endpoint
-- implements "find by sha, else insert" but V66 had no DB-level
-- UNIQUE on artifact_sha256, so two concurrent callers could both
-- pass the find_by_artifact_sha None check before either INSERT
-- committed, producing two rows for the same artifact and breaking
-- the content-addressed contract.
--
-- This migration adds the missing constraint. Existing rows must be
-- unique on artifact_sha256 already (the application layer enforced
-- it via find-then-insert under single-worker uvicorn, which has
-- never run concurrent registrations in practice).

ALTER TABLE model_registry
    ADD CONSTRAINT uq_model_registry_artifact_sha256
    UNIQUE (artifact_sha256);
