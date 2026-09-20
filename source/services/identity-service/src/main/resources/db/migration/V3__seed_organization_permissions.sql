INSERT INTO permissions(id,code,resource,action,description) VALUES
 ('11000000-0000-0000-0000-000000000001','organization:manage_branch','organization','manage_branch','Create and update branches'),
 ('11000000-0000-0000-0000-000000000002','organization:view_branch','organization','view_branch','View branches in scope'),
 ('11000000-0000-0000-0000-000000000003','organization:manage_employee','organization','manage_employee','Create and maintain employee profiles'),
 ('11000000-0000-0000-0000-000000000004','organization:assign_employee','organization','assign_employee','Assign employees to branches'),
 ('11000000-0000-0000-0000-000000000005','organization:manage_schedule','organization','manage_schedule','Publish branch schedules'),
 ('11000000-0000-0000-0000-000000000006','organization:self_attendance','organization','self_attendance','Record own attendance'),
 ('11000000-0000-0000-0000-000000000007','organization:record_attendance','organization','record_attendance','Record employee attendance exceptions'),
 ('11000000-0000-0000-0000-000000000008','organization:open_shift','organization','open_shift','Open a register shift'),
 ('11000000-0000-0000-0000-000000000009','organization:close_shift','organization','close_shift','Close and reconcile a register shift'),
 ('11000000-0000-0000-0000-000000000010','organization:approve_shift_variance','organization','approve_shift_variance','Approve material cash variance')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT '20000000-0000-0000-0000-000000000001'::uuid,id FROM permissions WHERE resource='organization'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT '20000000-0000-0000-0000-000000000002'::uuid,id FROM permissions
WHERE code IN ('organization:manage_branch','organization:view_branch','organization:manage_employee','organization:assign_employee','organization:manage_schedule','organization:record_attendance','organization:open_shift','organization:close_shift','organization:approve_shift_variance')
ON CONFLICT DO NOTHING;
