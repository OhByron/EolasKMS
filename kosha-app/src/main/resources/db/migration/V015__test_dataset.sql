-- ============================================================================
-- V015 — Synthetic test dataset for retention/aging report development
--
-- Generates ~300 documents spanning 10 years across 6 departments with
-- realistic lifecycle progression, retention reviews, audit trails,
-- and workflow history.
--
-- GUARD: only runs when the 'kosha_seed_test_data' database flag is set,
-- so it is safe to include in the migration chain.  To activate:
--   ALTER DATABASE kosha SET app.seed_test_data = 'true';
-- then re-run migrations.  To skip, do nothing — the migration records
-- itself as applied but inserts zero rows.
-- ============================================================================

DO $$
DECLARE
    -- ── Feature guard ────────────────────────────────────────────
    _enabled TEXT;

    -- ── Department IDs ───────────────────────────────────────────
    dept_general   UUID := '00000000-0000-0000-0000-000000000001'; -- already seeded
    dept_hr        UUID := '00000000-0000-0000-1000-000000000001';
    dept_legal     UUID := '00000000-0000-0000-1000-000000000002';
    dept_eng       UUID := '00000000-0000-0000-1000-000000000003';
    dept_finance   UUID := '00000000-0000-0000-1000-000000000004';
    dept_marketing UUID := '00000000-0000-0000-1000-000000000005';

    _depts UUID[] := ARRAY[
        '00000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-1000-000000000001',
        '00000000-0000-0000-1000-000000000002',
        '00000000-0000-0000-1000-000000000003',
        '00000000-0000-0000-1000-000000000004',
        '00000000-0000-0000-1000-000000000005'
    ];

    -- ── Retention policy IDs ─────────────────────────────────────
    pol_short      UUID := '00000000-0000-0000-2000-000000000001';
    pol_standard   UUID := '00000000-0000-0000-2000-000000000002';
    pol_medium     UUID := '00000000-0000-0000-2000-000000000003';
    pol_regulatory UUID := '00000000-0000-0000-2000-000000000004';
    pol_permanent  UUID := '00000000-0000-0000-2000-000000000005';

    _policies UUID[] := ARRAY[
        '00000000-0000-0000-2000-000000000001',
        '00000000-0000-0000-2000-000000000002',
        '00000000-0000-0000-2000-000000000003',
        '00000000-0000-0000-2000-000000000004',
        '00000000-0000-0000-2000-000000000005'
    ];

    -- ── User IDs (18 users across departments) ──────────────────
    _user_ids UUID[];

    -- ── Category IDs (looked up from existing seed) ─────────────
    cat_policy       UUID;
    cat_procedure    UUID;
    cat_form         UUID;
    cat_report       UUID;
    cat_specification UUID;
    cat_manual       UUID;
    _categories UUID[];

    -- ── MIME type IDs ────────────────────────────────────────────
    mime_pdf   UUID;
    mime_docx  UUID;
    mime_xlsx  UUID;
    mime_pptx  UUID;
    mime_txt   UUID;
    _mimes UUID[];

    -- ── Working variables ────────────────────────────────────────
    _statuses TEXT[] := ARRAY['DRAFT','IN_REVIEW','PUBLISHED','ARCHIVED','SUPERSEDED','LEGAL_HOLD','REJECTED'];
    _status_weights INT[] := ARRAY[8, 5, 40, 20, 10, 3, 4]; -- weighted distribution
    _status_total INT := 90;

    _doc_titles TEXT[] := ARRAY[
        'Employee Onboarding Guide','Data Retention Standard','Incident Response Plan',
        'Code Review Checklist','Budget Forecast Template','Brand Guidelines',
        'Travel Expense Policy','Server Hardening Procedure','Privacy Impact Assessment',
        'API Design Specification','Quarterly Sales Report','Customer Feedback Form',
        'Disaster Recovery Manual','Network Architecture Diagram','Vendor Assessment Form',
        'Change Management Procedure','Annual Compliance Report','Security Audit Checklist',
        'Product Roadmap Overview','Training Completion Form','Leave Request Procedure',
        'Software License Inventory','Risk Register Template','Board Meeting Minutes',
        'Accessibility Standards Guide','Release Notes Template','IT Asset Disposal Policy',
        'Performance Review Form','Knowledge Base Article Template','SLA Monitoring Report',
        'Contract Renewal Checklist','GDPR Compliance Procedure','Financial Reconciliation Manual',
        'Marketing Campaign Brief','Internal Memo Template','System Maintenance Log',
        'Whistleblower Policy','Patent Filing Procedure','Office Safety Manual',
        'Project Charter Template','Supplier Onboarding Form','Penetration Test Report',
        'Content Style Guide','Payroll Processing Procedure','Insurance Claims Form',
        'Capacity Planning Specification','Customer Satisfaction Survey Report','Benefits Enrollment Guide',
        'Regulatory Filing Checklist','Database Migration Procedure'
    ];

    _file_exts TEXT[] := ARRAY['.pdf','.docx','.xlsx','.pptx','.txt'];

    _i INT;
    _j INT;
    _doc_id UUID;
    _ver_id UUID;
    _dept_id UUID;
    _cat_id UUID;
    _mime_id UUID;
    _pol_id UUID;
    _user_id UUID;
    _reviewer_id UUID;
    _status TEXT;
    _created_at TIMESTAMPTZ;
    _ver_created TIMESTAMPTZ;
    _title TEXT;
    _num_versions INT;
    _ver_num INT;
    _roll INT;
    _cumul INT;
    _days_ago INT;
    _review_due TIMESTAMPTZ;
    _wf_def_id UUID;
    _wf_inst_id UUID;
    _wf_step_id UUID;

BEGIN
    -- ── Check feature guard ──────────────────────────────────────
    BEGIN
        _enabled := current_setting('app.seed_test_data');
    EXCEPTION WHEN OTHERS THEN
        _enabled := 'false';
    END;

    IF lower(coalesce(_enabled, 'false')) <> 'true' THEN
        RAISE NOTICE 'V015 test dataset SKIPPED — set app.seed_test_data = true to activate';
        RETURN;
    END IF;

    RAISE NOTICE 'V015 test dataset — generating synthetic data...';

    -- ══════════════════════════════════════════════════════════════
    -- 1. DEPARTMENTS
    -- ══════════════════════════════════════════════════════════════
    INSERT INTO ident.department (id, name, description, status, created_at)
    VALUES
        (dept_hr,        'Human Resources',    'People operations and talent management',   'ACTIVE', now() - interval '8 years'),
        (dept_legal,     'Legal & Compliance',  'Legal affairs, regulatory compliance',      'ACTIVE', now() - interval '8 years'),
        (dept_eng,       'Engineering',         'Software development and infrastructure',   'ACTIVE', now() - interval '7 years'),
        (dept_finance,   'Finance',             'Financial planning, accounting, treasury',  'ACTIVE', now() - interval '8 years'),
        (dept_marketing, 'Marketing',           'Brand, content, and demand generation',     'ACTIVE', now() - interval '5 years')
    ON CONFLICT (id) DO NOTHING;

    -- ══════════════════════════════════════════════════════════════
    -- 2. USERS  (3 per department: 1 admin, 1 editor, 1 contributor)
    -- ══════════════════════════════════════════════════════════════
    _user_ids := ARRAY[]::UUID[];

    FOR _i IN 0..5 LOOP
        FOR _j IN 1..3 LOOP
            DECLARE
                _uid UUID := gen_random_uuid();
                _role TEXT;
                _name TEXT;
                _dept UUID := _depts[_i + 1];
            BEGIN
                CASE _j
                    WHEN 1 THEN _role := 'DEPT_ADMIN';
                    WHEN 2 THEN _role := 'EDITOR';
                    WHEN 3 THEN _role := 'CONTRIBUTOR';
                END CASE;

                _name := (ARRAY[
                    'Alice','Bob','Carol','David','Emma','Frank',
                    'Grace','Henry','Irene','James','Karen','Leo',
                    'Maria','Nathan','Olivia','Paul','Quinn','Rachel'
                ])[_i * 3 + _j];

                INSERT INTO ident.user_profile (id, keycloak_id, display_name, email, department_id, role, status, created_at)
                VALUES (
                    _uid,
                    gen_random_uuid(),
                    _name || ' ' || (ARRAY['Adams','Baker','Clark','Davis','Evans','Foster',
                                            'Grant','Harris','Irving','Jones','King','Lloyd',
                                            'Moore','Nash','Owen','Price','Quinn','Ross'])[_i * 3 + _j],
                    lower(_name) || '.' || lower(_role) || '.' || _i || '@kosha-test.local',
                    _dept,
                    CASE WHEN _i = 0 AND _j = 1 THEN 'GLOBAL_ADMIN' ELSE _role END,
                    'ACTIVE',
                    now() - interval '7 years' + (_i * interval '3 months')
                );

                _user_ids := array_append(_user_ids, _uid);
            END;
        END LOOP;
    END LOOP;

    -- ══════════════════════════════════════════════════════════════
    -- 3. RETENTION POLICIES
    -- ══════════════════════════════════════════════════════════════
    INSERT INTO ret.retention_policy (id, name, description, retention_period, review_interval, action_on_expiry, department_id, status, created_at)
    VALUES
        (pol_short,      'Short-Term',   'Temporary documents, drafts, meeting notes',             '90 days',  NULL,       'DELETE',  NULL,        'ACTIVE', now() - interval '6 years'),
        (pol_standard,   'Standard',     'General business documents with annual review',          '1 year',   '6 months', 'REVIEW',  NULL,        'ACTIVE', now() - interval '6 years'),
        (pol_medium,     'Medium-Term',  'Project documentation, internal reports',                '3 years',  '1 year',   'ARCHIVE', NULL,        'ACTIVE', now() - interval '6 years'),
        (pol_regulatory, 'Regulatory',   'Financial records, compliance documentation',            '7 years',  '1 year',   'REVIEW',  dept_legal,  'ACTIVE', now() - interval '6 years'),
        (pol_permanent,  'Permanent',    'Corporate policies, legal agreements, IP documentation', '10 years', '2 years',  'ARCHIVE', NULL,        'ACTIVE', now() - interval '6 years')
    ON CONFLICT (id) DO NOTHING;

    -- ══════════════════════════════════════════════════════════════
    -- 4. LOOK UP EXISTING CATEGORY & MIME TYPE IDS
    -- ══════════════════════════════════════════════════════════════
    SELECT id INTO cat_policy       FROM doc.document_category WHERE name = 'Policy'        LIMIT 1;
    SELECT id INTO cat_procedure    FROM doc.document_category WHERE name = 'Procedure'     LIMIT 1;
    SELECT id INTO cat_form         FROM doc.document_category WHERE name = 'Form'          LIMIT 1;
    SELECT id INTO cat_report       FROM doc.document_category WHERE name = 'Report'        LIMIT 1;
    SELECT id INTO cat_specification FROM doc.document_category WHERE name = 'Specification' LIMIT 1;
    SELECT id INTO cat_manual       FROM doc.document_category WHERE name = 'Manual'        LIMIT 1;
    _categories := ARRAY[cat_policy, cat_procedure, cat_form, cat_report, cat_specification, cat_manual];

    SELECT id INTO mime_pdf  FROM doc.mime_type WHERE extension = '.pdf'  LIMIT 1;
    SELECT id INTO mime_docx FROM doc.mime_type WHERE extension = '.docx' LIMIT 1;
    SELECT id INTO mime_xlsx FROM doc.mime_type WHERE extension = '.xlsx' LIMIT 1;
    SELECT id INTO mime_pptx FROM doc.mime_type WHERE extension = '.pptx' LIMIT 1;
    SELECT id INTO mime_txt  FROM doc.mime_type WHERE extension = '.txt'  LIMIT 1;
    _mimes := ARRAY[mime_pdf, mime_docx, mime_xlsx, mime_pptx, mime_txt];

    -- ══════════════════════════════════════════════════════════════
    -- 5. GENERATE DOCUMENTS  (~300)
    -- ══════════════════════════════════════════════════════════════
    FOR _i IN 1..300 LOOP
        _doc_id  := gen_random_uuid();
        _dept_id := _depts[1 + (_i % 6)];
        _cat_id  := _categories[1 + (_i % 6)];

        -- Pick a MIME type (weighted toward PDF/DOCX)
        _roll := floor(random() * 10)::INT;
        IF _roll < 4 THEN _mime_id := mime_pdf;
        ELSIF _roll < 7 THEN _mime_id := mime_docx;
        ELSIF _roll < 8 THEN _mime_id := mime_xlsx;
        ELSIF _roll < 9 THEN _mime_id := mime_pptx;
        ELSE _mime_id := mime_txt;
        END IF;

        -- Pick user from same department (3 users per dept, dept index = _i%6)
        _user_id := _user_ids[1 + ((_i % 6) * 3) + (_i % 3)];

        -- Weighted random status
        _roll := floor(random() * _status_total)::INT;
        _cumul := 0;
        _status := 'DRAFT';
        FOR _j IN 1..array_length(_statuses, 1) LOOP
            _cumul := _cumul + _status_weights[_j];
            IF _roll < _cumul THEN
                _status := _statuses[_j];
                EXIT;
            END IF;
        END LOOP;

        -- Age: exponential-ish distribution — more recent, fewer ancient
        -- Uses a squared random to skew toward smaller values
        _days_ago := floor(power(random(), 0.6) * 3650)::INT;  -- up to ~10 years
        _created_at := now() - (_days_ago || ' days')::INTERVAL
                            - (floor(random() * 12) || ' hours')::INTERVAL;

        -- Retention policy: weighted by department affinity
        IF _dept_id = dept_legal OR _dept_id = dept_finance THEN
            _pol_id := _policies[3 + (_i % 2)];  -- medium / regulatory
        ELSIF _status = 'LEGAL_HOLD' THEN
            _pol_id := pol_regulatory;
        ELSE
            _pol_id := _policies[1 + (_i % 5)];
        END IF;

        -- Title: cycle through titles with department prefix
        _title := _doc_titles[1 + (_i % array_length(_doc_titles, 1))];

        INSERT INTO doc.document (
            id, doc_number, title, description, department_id, category_id,
            status, storage_mode, mime_type_id, workflow_type,
            retention_policy_id, review_cycle, next_review_at,
            created_by, created_at, updated_at
        ) VALUES (
            _doc_id,
            'DOC-' || lpad(_i::TEXT, 5, '0'),
            _title,
            'Synthetic test document #' || _i || ' — ' || _title,
            _dept_id,
            _cat_id,
            _status,
            CASE WHEN random() < 0.8 THEN 'VAULT' ELSE 'CONNECTOR' END,
            _mime_id,
            CASE WHEN random() < 0.3 THEN 'LINEAR' WHEN random() < 0.1 THEN 'PARALLEL' ELSE 'NONE' END,
            _pol_id,
            CASE WHEN random() < 0.6 THEN '1 year' WHEN random() < 0.3 THEN '6 months' ELSE NULL END,
            CASE WHEN _status IN ('PUBLISHED','ARCHIVED') THEN _created_at + (floor(random() * 365 + 90) || ' days')::INTERVAL ELSE NULL END,
            _user_id,
            _created_at,
            _created_at + (floor(random() * greatest(_days_ago, 1)) || ' days')::INTERVAL
        );

        -- Document owner
        INSERT INTO doc.document_owner (document_id, user_profile_id)
        VALUES (_doc_id, _user_id)
        ON CONFLICT DO NOTHING;

        -- ── VERSIONS (1-5 per document) ─────────────────────────
        _num_versions := 1 + floor(random() * 4)::INT;
        -- Older published docs tend to have more versions
        IF _days_ago > 1000 AND _status = 'PUBLISHED' THEN
            _num_versions := greatest(_num_versions, 3);
        END IF;

        _ver_created := _created_at;

        FOR _ver_num IN 1.._num_versions LOOP
            _ver_id := gen_random_uuid();

            -- Each version created some time after the previous
            IF _ver_num > 1 THEN
                _ver_created := _ver_created + (floor(random() * 60 + 1) || ' days')::INTERVAL;
            END IF;

            INSERT INTO doc.document_version (
                id, document_id, version_number, file_name,
                file_size_bytes, content_hash, storage_key, change_summary,
                status, created_by, created_at, updated_at
            ) VALUES (
                _ver_id,
                _doc_id,
                _ver_num || '.0',
                replace(lower(_title), ' ', '_') || '_v' || _ver_num || _file_exts[1 + (_i % 5)],
                floor(random() * 10000000 + 10000)::BIGINT,
                md5(gen_random_uuid()::TEXT || _ver_num::TEXT),
                'vault/' || extract(year from _ver_created) || '/' || lpad(extract(month from _ver_created)::TEXT, 2, '0') || '/' || _doc_id || '/' || _ver_id,
                CASE _ver_num
                    WHEN 1 THEN 'Initial upload'
                    WHEN 2 THEN 'Revised after review feedback'
                    WHEN 3 THEN 'Updated for policy changes'
                    WHEN 4 THEN 'Annual refresh'
                    ELSE 'Minor corrections'
                END,
                CASE
                    WHEN _ver_num = _num_versions THEN _status
                    ELSE 'SUPERSEDED'
                END,
                _user_id,
                _ver_created,
                _ver_created
            );

            -- Version metadata (AI-generated summaries)
            INSERT INTO doc.version_metadata (
                id, version_id, summary, ai_confidence, human_reviewed,
                reviewed_by, created_at
            ) VALUES (
                gen_random_uuid(),
                _ver_id,
                'AI-generated summary for ' || _title || ' version ' || _ver_num || '.0. '
                    || 'This document covers key aspects of ' || lower(_title) || ' within the organisation.',
                (0.55 + random() * 0.40)::DECIMAL(3,2),
                random() < 0.4,
                CASE WHEN random() < 0.4 THEN _user_ids[1 + ((_i % 6) * 3 + 1)] ELSE NULL END,
                _ver_created + interval '5 minutes'
            );
        END LOOP;

        -- ── RETENTION REVIEWS ────────────────────────────────────
        -- Create reviews for documents older than their policy period
        IF _status IN ('PUBLISHED','ARCHIVED','LEGAL_HOLD') AND _days_ago > 90 THEN
            -- Past review (completed)
            IF random() < 0.6 THEN
                _review_due := _created_at + (floor(random() * 365 + 90) || ' days')::INTERVAL;
                _reviewer_id := _user_ids[1 + ((_i % 6) * 3)]; -- dept admin

                INSERT INTO ret.retention_review (
                    id, document_id, policy_id, due_at,
                    completed_at, reviewed_by, outcome, notes, created_at
                ) VALUES (
                    gen_random_uuid(), _doc_id, _pol_id,
                    _review_due,
                    _review_due + (floor(random() * 14) || ' days')::INTERVAL,
                    _reviewer_id,
                    (ARRAY['RETAIN','ARCHIVE','DESTROY'])[1 + floor(random() * 3)::INT],
                    'Periodic retention review completed.',
                    _review_due - interval '7 days'
                );
            END IF;

            -- Overdue review (not completed, due in the past)
            IF random() < 0.35 THEN
                _review_due := now() - (floor(random() * 180 + 1) || ' days')::INTERVAL;

                INSERT INTO ret.retention_review (
                    id, document_id, policy_id, due_at,
                    completed_at, reviewed_by, outcome, notes, created_at
                ) VALUES (
                    gen_random_uuid(), _doc_id, _pol_id,
                    _review_due,
                    NULL, NULL, NULL,
                    NULL,
                    _review_due - interval '14 days'
                );
            END IF;

            -- Pending review (due in the future)
            IF random() < 0.25 THEN
                _review_due := now() + (floor(random() * 180 + 1) || ' days')::INTERVAL;

                INSERT INTO ret.retention_review (
                    id, document_id, policy_id, due_at,
                    completed_at, reviewed_by, outcome, notes, created_at
                ) VALUES (
                    gen_random_uuid(), _doc_id, _pol_id,
                    _review_due,
                    NULL, NULL, NULL,
                    NULL,
                    now() - interval '7 days'
                );
            END IF;
        END IF;

        -- ── AUDIT EVENTS ─────────────────────────────────────────
        -- doc.created
        INSERT INTO audit.event (id, event_type, aggregate_type, aggregate_id, actor_id, department_id, payload, occurred_at)
        VALUES (
            gen_random_uuid(), 'doc.created', 'DOCUMENT', _doc_id, _user_id, _dept_id,
            jsonb_build_object('title', _title, 'departmentId', _dept_id),
            _created_at
        );

        -- doc.status.changed for non-DRAFT documents
        IF _status <> 'DRAFT' THEN
            INSERT INTO audit.event (id, event_type, aggregate_type, aggregate_id, actor_id, department_id, payload, occurred_at)
            VALUES (
                gen_random_uuid(), 'doc.status.changed', 'DOCUMENT', _doc_id, _user_id, _dept_id,
                jsonb_build_object('previousStatus', 'DRAFT', 'newStatus',
                    CASE WHEN _status IN ('PUBLISHED','ARCHIVED','SUPERSEDED','LEGAL_HOLD') THEN 'IN_REVIEW' ELSE _status END),
                _created_at + (floor(random() * 14 + 1) || ' days')::INTERVAL
            );

            -- Second status transition for terminal states
            IF _status IN ('PUBLISHED','ARCHIVED','SUPERSEDED','LEGAL_HOLD') THEN
                INSERT INTO audit.event (id, event_type, aggregate_type, aggregate_id, actor_id, department_id, payload, occurred_at)
                VALUES (
                    gen_random_uuid(), 'doc.status.changed', 'DOCUMENT', _doc_id,
                    _user_ids[1 + ((_i % 6) * 3 + 1)],  -- editor
                    _dept_id,
                    jsonb_build_object('previousStatus', 'IN_REVIEW', 'newStatus', _status),
                    _created_at + (floor(random() * 30 + 15) || ' days')::INTERVAL
                );
            END IF;
        END IF;

    END LOOP;

    -- ══════════════════════════════════════════════════════════════
    -- 6. WORKFLOW DEFINITIONS & INSTANCES
    -- ══════════════════════════════════════════════════════════════

    -- One workflow definition per department
    FOR _i IN 0..5 LOOP
        _wf_def_id := gen_random_uuid();
        _dept_id := _depts[_i + 1];

        INSERT INTO wf.workflow_definition (id, name, description, workflow_type, department_id, is_default, created_at)
        VALUES (
            _wf_def_id,
            'Standard Review — ' || (ARRAY['General','HR','Legal','Engineering','Finance','Marketing'])[_i + 1],
            'Default two-step review and approval workflow',
            'LINEAR',
            _dept_id,
            TRUE,
            now() - interval '5 years'
        );

        -- Two steps: Review then Approve
        INSERT INTO wf.workflow_step_definition (id, workflow_def_id, step_order, name, assignee_role, action_type, timeout_hours, created_at)
        VALUES
            (gen_random_uuid(), _wf_def_id, 1, 'Peer Review',    'EDITOR',     'REVIEW',  72, now() - interval '5 years'),
            (gen_random_uuid(), _wf_def_id, 2, 'Manager Approval','DEPT_ADMIN', 'APPROVE', 48, now() - interval '5 years');

        -- Create workflow instances for some PUBLISHED/ARCHIVED/REJECTED docs in this dept
        FOR _doc_id, _ver_id, _created_at, _status IN
            SELECT d.id, dv.id, d.created_at, d.status
            FROM doc.document d
            JOIN doc.document_version dv ON dv.document_id = d.id
                AND dv.version_number = '1.0'
            WHERE d.department_id = _dept_id
              AND d.status IN ('PUBLISHED','ARCHIVED','REJECTED')
              AND random() < 0.5
            LIMIT 15
        LOOP
            _wf_inst_id := gen_random_uuid();

            INSERT INTO wf.workflow_instance (
                id, workflow_def_id, document_id, version_id,
                initiated_by, status, started_at, completed_at, created_at
            ) VALUES (
                _wf_inst_id, _wf_def_id, _doc_id, _ver_id,
                _user_ids[1 + (_i * 3 + 2)],  -- contributor
                CASE WHEN _status = 'REJECTED' THEN 'REJECTED' ELSE 'COMPLETED' END,
                _created_at + interval '2 days',
                _created_at + interval '8 days',
                _created_at + interval '2 days'
            );

            -- Step instances
            -- Step 1: Review (always completed)
            _wf_step_id := gen_random_uuid();
            INSERT INTO wf.workflow_step_instance (
                id, workflow_inst_id, step_def_id, assigned_to,
                status, comments, decided_at, created_at
            )
            SELECT
                _wf_step_id, _wf_inst_id, sd.id,
                _user_ids[1 + (_i * 3 + 1)],  -- editor
                'APPROVED',
                'Reviewed and looks good.',
                _created_at + interval '4 days',
                _created_at + interval '2 days'
            FROM wf.workflow_step_definition sd
            WHERE sd.workflow_def_id = _wf_def_id AND sd.step_order = 1;

            -- Step 2: Approval
            INSERT INTO wf.workflow_step_instance (
                id, workflow_inst_id, step_def_id, assigned_to,
                status, comments, decided_at, created_at
            )
            SELECT
                gen_random_uuid(), _wf_inst_id, sd.id,
                _user_ids[1 + (_i * 3)],  -- dept admin
                CASE WHEN _status = 'REJECTED' THEN 'REJECTED' ELSE 'APPROVED' END,
                CASE WHEN _status = 'REJECTED' THEN 'Does not meet current standards.' ELSE 'Approved for publication.' END,
                _created_at + interval '7 days',
                _created_at + interval '4 days'
            FROM wf.workflow_step_definition sd
            WHERE sd.workflow_def_id = _wf_def_id AND sd.step_order = 2;

            -- Audit: workflow completed/rejected
            INSERT INTO audit.event (id, event_type, aggregate_type, aggregate_id, actor_id, department_id, payload, occurred_at)
            VALUES (
                gen_random_uuid(),
                CASE WHEN _status = 'REJECTED' THEN 'wf.rejected' ELSE 'wf.completed' END,
                'WORKFLOW', _wf_inst_id,
                _user_ids[1 + (_i * 3)],
                _dept_id,
                jsonb_build_object('documentId', _doc_id, 'workflowDefId', _wf_def_id),
                _created_at + interval '8 days'
            );
        END LOOP;
    END LOOP;

    -- ══════════════════════════════════════════════════════════════
    -- 7. TAXONOMY TERMS & CLASSIFICATIONS
    -- ══════════════════════════════════════════════════════════════
    DECLARE
        _term_ids UUID[] := ARRAY[]::UUID[];
        _tid UUID;
        _term_labels TEXT[] := ARRAY[
            'Information Security','Human Resources','Financial Reporting','Legal Compliance',
            'Software Engineering','Project Management','Quality Assurance','Risk Management',
            'Data Governance','Customer Relations','Supply Chain','Health & Safety',
            'Intellectual Property','Training & Development','Corporate Governance',
            'Disaster Recovery','Change Management','Vendor Management','Privacy',
            'Accessibility'
        ];
    BEGIN
        FOR _i IN 1..array_length(_term_labels, 1) LOOP
            _tid := gen_random_uuid();
            _term_ids := array_append(_term_ids, _tid);

            INSERT INTO tax.taxonomy_term (id, label, normalized_label, description, source, status, created_at)
            VALUES (
                _tid,
                _term_labels[_i],
                lower(replace(_term_labels[_i], ' ', '_')),
                'Taxonomy term: ' || _term_labels[_i],
                CASE WHEN _i <= 10 THEN 'SEED' WHEN _i <= 15 THEN 'AI_GENERATED' ELSE 'MANUAL' END,
                'ACTIVE',
                now() - interval '4 years'
            );
        END LOOP;

        -- Classify documents against taxonomy terms
        FOR _doc_id IN
            SELECT id FROM doc.document WHERE deleted_at IS NULL ORDER BY random() LIMIT 200
        LOOP
            -- 1-3 classifications per document
            FOR _j IN 1..least(floor(random() * 3 + 1)::INT, array_length(_term_ids, 1)) LOOP
                INSERT INTO tax.document_classification (
                    id, document_id, term_id, confidence, source, created_by, created_at
                ) VALUES (
                    gen_random_uuid(),
                    _doc_id,
                    _term_ids[1 + floor(random() * array_length(_term_ids, 1))::INT],
                    (0.50 + random() * 0.45)::DECIMAL(3,2),
                    CASE WHEN random() < 0.7 THEN 'AI' ELSE 'MANUAL' END,
                    _user_ids[1 + floor(random() * array_length(_user_ids, 1))::INT],
                    now() - (floor(random() * 1000) || ' days')::INTERVAL
                )
                ON CONFLICT (document_id, term_id) DO NOTHING;
            END LOOP;
        END LOOP;
    END;

    RAISE NOTICE 'V015 test dataset — generation complete.';
END;
$$;

-- ══════════════════════════════════════════════════════════════════
-- 8. REFRESH MATERIALIZED VIEWS
-- ══════════════════════════════════════════════════════════════════
-- Use non-concurrent refresh: safe for first population and small datasets.
-- Production should use CONCURRENTLY via a scheduled job.
REFRESH MATERIALIZED VIEW report.retention_compliance;
REFRESH MATERIALIZED VIEW report.department_activity;
REFRESH MATERIALIZED VIEW report.taxonomy_coverage;
