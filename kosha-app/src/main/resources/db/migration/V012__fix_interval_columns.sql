-- V012: Change interval columns to varchar for Hibernate compatibility
-- PostgreSQL INTERVAL type doesn't map cleanly to JPA String; use varchar instead

ALTER TABLE ret.retention_policy ALTER COLUMN retention_period TYPE VARCHAR(50) USING retention_period::VARCHAR;
ALTER TABLE ret.retention_policy ALTER COLUMN review_interval TYPE VARCHAR(50) USING review_interval::VARCHAR;
ALTER TABLE doc.document ALTER COLUMN review_cycle TYPE VARCHAR(50) USING review_cycle::VARCHAR;
