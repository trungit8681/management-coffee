INSERT INTO permissions(id,code,resource,action,description) VALUES
 ('14000000-0000-0000-0000-000000000001','order:cancel','order','cancel','Cancel unpaid branch orders'),
 ('14000000-0000-0000-0000-000000000002','order:approve_cancel','order','approve_cancel','Approve cancellation of paid branch orders'),
 ('14000000-0000-0000-0000-000000000003','payment:refund_cash','payment','refund_cash','Record approved cash refund')
ON CONFLICT(code) DO NOTHING;
INSERT INTO role_permissions(role_id,permission_id)
SELECT '20000000-0000-0000-0000-000000000001'::uuid,id FROM permissions
WHERE code IN ('order:cancel','order:approve_cancel','payment:refund_cash') ON CONFLICT DO NOTHING;
INSERT INTO role_permissions(role_id,permission_id)
SELECT '20000000-0000-0000-0000-000000000002'::uuid,id FROM permissions
WHERE code IN ('order:cancel','order:approve_cancel','payment:refund_cash') ON CONFLICT DO NOTHING;
