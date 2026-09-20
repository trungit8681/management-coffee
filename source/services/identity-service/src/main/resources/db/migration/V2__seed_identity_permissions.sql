INSERT INTO permissions (id, code, resource, action, description) VALUES
    ('10000000-0000-0000-0000-000000000001', 'identity:create', 'identity', 'create', 'Create identity accounts'),
    ('10000000-0000-0000-0000-000000000002', 'identity:change_status', 'identity', 'change_status', 'Lock, unlock, or disable accounts'),
    ('10000000-0000-0000-0000-000000000003', 'identity:manage_roles', 'identity', 'manage_roles', 'Create roles and manage permissions'),
    ('10000000-0000-0000-0000-000000000004', 'identity:assign_role', 'identity', 'assign_role', 'Assign roles within an authorized branch scope'),
    ('10000000-0000-0000-0000-000000000005', 'identity:view_audit', 'identity', 'view_audit', 'View identity audit events')
ON CONFLICT (code) DO NOTHING;

INSERT INTO roles (id, code, name, description) VALUES
    ('20000000-0000-0000-0000-000000000001', 'SYSTEM_ADMIN', 'System Administrator', 'Global identity administrator'),
    ('20000000-0000-0000-0000-000000000002', 'BRANCH_MANAGER', 'Branch Manager', 'Identity manager restricted to assigned branches')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT '20000000-0000-0000-0000-000000000001'::uuid, id FROM permissions
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT '20000000-0000-0000-0000-000000000002'::uuid, id
FROM permissions
WHERE code = 'identity:assign_role'
ON CONFLICT DO NOTHING;
