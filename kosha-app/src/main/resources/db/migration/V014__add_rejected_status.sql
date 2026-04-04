-- V014: Add missing document statuses

INSERT INTO doc.document_status_lookup (code, display_name, sort_order) VALUES
    ('REJECTED', 'Rejected', 35)
ON CONFLICT (code) DO NOTHING;
