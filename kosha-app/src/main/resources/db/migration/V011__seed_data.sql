-- V011: Seed data for bootstrapping the system

-- Default department
INSERT INTO ident.department (id, name, description, status)
VALUES ('00000000-0000-0000-0000-000000000001', 'General', 'Default department for initial setup', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- Default document categories
INSERT INTO doc.document_category (id, name, description, department_id, status) VALUES
    (gen_random_uuid(), 'Policy', 'Organizational policies and guidelines', NULL, 'ACTIVE'),
    (gen_random_uuid(), 'Procedure', 'Standard operating procedures', NULL, 'ACTIVE'),
    (gen_random_uuid(), 'Form', 'Forms and templates', NULL, 'ACTIVE'),
    (gen_random_uuid(), 'Report', 'Reports and analysis documents', NULL, 'ACTIVE'),
    (gen_random_uuid(), 'Specification', 'Technical specifications', NULL, 'ACTIVE'),
    (gen_random_uuid(), 'Manual', 'User and reference manuals', NULL, 'ACTIVE')
ON CONFLICT DO NOTHING;
