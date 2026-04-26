-- V037: Synonyms (aliases) for taxonomy terms.
--
-- Distinct from related terms: aliases are alternative surface forms of the
-- *same* concept (WASH = "Water Sanitation Hygiene" = "WatSan"), where related
-- terms are different concepts that travel together (WASH ↔ Public Health).
--
-- The Stage-1 LLM classification prompt feeds these aliases alongside each
-- canonical term so Gemma maps any surface form to the canonical termId
-- instead of creating a duplicate CANDIDATE.
--
-- The Stage-2 propose-new prompt may also suggest aliases for new candidate
-- terms; admin then accepts/edits them on candidate promotion.

CREATE TABLE tax.taxonomy_term_alias (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    term_id                  UUID NOT NULL REFERENCES tax.taxonomy_term(id) ON DELETE CASCADE,
    alias_label              VARCHAR(500) NOT NULL,
    normalized_alias_label   VARCHAR(500) NOT NULL,
    source                   VARCHAR(30) NOT NULL CHECK (source IN ('AI_SUGGESTED', 'MANUAL')),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A given alias label can only appear once per term. Distinct terms can
    -- share an alias surface form (rare but legal — e.g. an acronym that two
    -- terms both go by; admin should resolve via merge but we don't enforce).
    UNIQUE (term_id, normalized_alias_label)
);

CREATE INDEX idx_term_alias_term ON tax.taxonomy_term_alias(term_id);
CREATE INDEX idx_term_alias_norm ON tax.taxonomy_term_alias(normalized_alias_label);
