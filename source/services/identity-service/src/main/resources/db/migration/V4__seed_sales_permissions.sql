INSERT INTO permissions(id,code,resource,action,description) VALUES
 ('12000000-0000-0000-0000-000000000001','catalog:manage_product','catalog','manage_product','Manage products'),
 ('12000000-0000-0000-0000-000000000002','catalog:manage_price','catalog','manage_price','Manage branch prices'),
 ('12000000-0000-0000-0000-000000000003','catalog:view_menu','catalog','view_menu','View branch menu'),
 ('12000000-0000-0000-0000-000000000004','order:create','order','create','Create branch orders'),
 ('12000000-0000-0000-0000-000000000005','order:view','order','view','View branch orders'),
 ('12000000-0000-0000-0000-000000000006','order:collect_cash','order','collect_cash','Read locked order cash amount and confirm payment'),
 ('12000000-0000-0000-0000-000000000007','payment:collect_cash','payment','collect_cash','Record cash collection'),
 ('12000000-0000-0000-0000-000000000008','payment:view_cash','payment','view_cash','View branch cash receipt')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT '20000000-0000-0000-0000-000000000001'::uuid,id FROM permissions
WHERE resource IN ('catalog','order','payment') ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT '20000000-0000-0000-0000-000000000002'::uuid,id FROM permissions
WHERE resource IN ('catalog','order','payment') ON CONFLICT DO NOTHING;
