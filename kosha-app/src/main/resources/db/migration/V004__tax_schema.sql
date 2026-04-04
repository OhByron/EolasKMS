-- ============================================================
-- V004: Taxonomy module — terms, edges, closure, classifications
-- ============================================================

CREATE SCHEMA IF NOT EXISTS tax;

-- Taxonomy terms (nodes in the DAG)
CREATE TABLE tax.taxonomy_term (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    label               VARCHAR(500) NOT NULL,
    normalized_label    VARCHAR(500) NOT NULL,
    description         TEXT,
    source              VARCHAR(30) NOT NULL
                        CHECK (source IN ('SEED', 'AI_GENERATED', 'MANUAL')),
    source_ref          VARCHAR(500),
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'CANDIDATE', 'MERGED', 'DEPRECATED')),
    merged_into_id      UUID REFERENCES tax.taxonomy_term(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_taxterm_normalized ON tax.taxonomy_term(normalized_label);
CREATE INDEX idx_taxterm_status     ON tax.taxonomy_term(status);
CREATE INDEX idx_taxterm_source     ON tax.taxonomy_term(source);
CREATE INDEX idx_taxterm_merged     ON tax.taxonomy_term(merged_into_id) WHERE merged_into_id IS NOT NULL;

-- DAG edges (parent→child, related, see-also)
CREATE TABLE tax.taxonomy_edge (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_term_id  UUID NOT NULL REFERENCES tax.taxonomy_term(id) ON DELETE CASCADE,
    child_term_id   UUID NOT NULL REFERENCES tax.taxonomy_term(id) ON DELETE CASCADE,
    edge_type       VARCHAR(20) NOT NULL DEFAULT 'BROADER'
                    CHECK (edge_type IN ('BROADER', 'RELATED', 'SEE_ALSO')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (parent_term_id, child_term_id, edge_type),
    CHECK (parent_term_id != child_term_id)
);

CREATE INDEX idx_taxedge_parent ON tax.taxonomy_edge(parent_term_id);
CREATE INDEX idx_taxedge_child  ON tax.taxonomy_edge(child_term_id);

-- Closure table for efficient ancestor/descendant queries
CREATE TABLE tax.taxonomy_closure (
    ancestor_id     UUID NOT NULL REFERENCES tax.taxonomy_term(id) ON DELETE CASCADE,
    descendant_id   UUID NOT NULL REFERENCES tax.taxonomy_term(id) ON DELETE CASCADE,
    depth           INTEGER NOT NULL CHECK (depth >= 0),
    PRIMARY KEY (ancestor_id, descendant_id)
);

CREATE INDEX idx_taxclosure_desc ON tax.taxonomy_closure(descendant_id);

-- Document → taxonomy term classification
CREATE TABLE tax.document_classification (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID NOT NULL REFERENCES doc.document(id) ON DELETE CASCADE,
    term_id         UUID NOT NULL REFERENCES tax.taxonomy_term(id) ON DELETE CASCADE,
    confidence      DECIMAL(3,2) CHECK (confidence >= 0 AND confidence <= 1),
    source          VARCHAR(20) NOT NULL
                    CHECK (source IN ('AI', 'MANUAL')),
    created_by      UUID REFERENCES ident.user_profile(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, term_id)
);

CREATE INDEX idx_docclass_doc  ON tax.document_classification(document_id);
CREATE INDEX idx_docclass_term ON tax.document_classification(term_id);

-- Raw extracted keywords (before taxonomy mapping)
CREATE TABLE tax.extracted_keyword (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id      UUID NOT NULL REFERENCES doc.document_version(id) ON DELETE CASCADE,
    keyword         VARCHAR(500) NOT NULL,
    frequency       INTEGER,
    confidence      DECIMAL(3,2) CHECK (confidence >= 0 AND confidence <= 1),
    mapped_term_id  UUID REFERENCES tax.taxonomy_term(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_extkw_version ON tax.extracted_keyword(version_id);
CREATE INDEX idx_extkw_mapped  ON tax.extracted_keyword(mapped_term_id) WHERE mapped_term_id IS NOT NULL;

-- Seed taxonomy metadata
CREATE TABLE tax.taxonomy_seed (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    version         VARCHAR(50),
    imported_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    term_count      INTEGER
);

-- Trigger
CREATE OR REPLACE FUNCTION tax.set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_taxterm_updated
    BEFORE UPDATE ON tax.taxonomy_term
    FOR EACH ROW EXECUTE FUNCTION tax.set_updated_at();
