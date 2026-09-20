INSERT INTO permissions(id,code,resource,action,description) VALUES
 ('13000000-0000-0000-0000-000000000001','inventory:manage_ingredient','inventory','manage_ingredient','Manage ingredients'),
 ('13000000-0000-0000-0000-000000000002','inventory:receive','inventory','receive','Receive branch stock'),
 ('13000000-0000-0000-0000-000000000003','inventory:deduct','inventory','deduct','Deduct branch stock'),
 ('13000000-0000-0000-0000-000000000004','inventory:view','inventory','view','View branch stock'),
 ('13000000-0000-0000-0000-000000000005','procurement:manage_supplier','procurement','manage_supplier','Manage suppliers'),
 ('13000000-0000-0000-0000-000000000006','procurement:create_po','procurement','create_po','Create purchase orders'),
 ('13000000-0000-0000-0000-000000000007','procurement:approve_po','procurement','approve_po','Approve purchase orders'),
 ('13000000-0000-0000-0000-000000000008','procurement:view_po','procurement','view_po','View purchase orders'),
 ('13000000-0000-0000-0000-000000000009','loyalty:manage_customer','loyalty','manage_customer','Create loyalty customers'),
 ('13000000-0000-0000-0000-000000000010','loyalty:adjust_points','loyalty','adjust_points','Adjust point balance'),
 ('13000000-0000-0000-0000-000000000011','loyalty:reserve_points','loyalty','reserve_points','Reserve customer points'),
 ('13000000-0000-0000-0000-000000000012','loyalty:commit_points','loyalty','commit_points','Commit or release reserved points'),
 ('13000000-0000-0000-0000-000000000013','loyalty:view_points','loyalty','view_points','View point wallet'),
 ('13000000-0000-0000-0000-000000000014','promotion:manage','promotion','manage','Manage vouchers'),
 ('13000000-0000-0000-0000-000000000015','promotion:reserve','promotion','reserve','Reserve voucher use'),
 ('13000000-0000-0000-0000-000000000016','promotion:commit','promotion','commit','Commit or release voucher use'),
 ('13000000-0000-0000-0000-000000000017','promotion:view','promotion','view','View branch vouchers'),
 ('13000000-0000-0000-0000-000000000018','fulfillment:create','fulfillment','create','Create delivery'),
 ('13000000-0000-0000-0000-000000000019','fulfillment:view','fulfillment','view','View delivery'),
 ('13000000-0000-0000-0000-000000000020','fulfillment:assign','fulfillment','assign','Assign driver'),
 ('13000000-0000-0000-0000-000000000021','fulfillment:complete','fulfillment','complete','Complete or fail delivery'),
 ('13000000-0000-0000-0000-000000000022','notification:manage_template','notification','manage_template','Manage notification templates'),
 ('13000000-0000-0000-0000-000000000023','notification:enqueue','notification','enqueue','Enqueue notification'),
 ('13000000-0000-0000-0000-000000000024','notification:view_inbox','notification','view_inbox','View notification inbox')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT '20000000-0000-0000-0000-000000000001'::uuid,id FROM permissions
WHERE resource IN ('inventory','procurement','loyalty','promotion','fulfillment','notification') ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT '20000000-0000-0000-0000-000000000002'::uuid,id FROM permissions
WHERE code IN (
 'inventory:manage_ingredient','inventory:receive','inventory:deduct','inventory:view',
 'procurement:create_po','procurement:approve_po','procurement:view_po',
 'promotion:manage','promotion:reserve','promotion:commit','promotion:view',
 'fulfillment:create','fulfillment:view','fulfillment:assign','fulfillment:complete',
 'notification:enqueue','notification:view_inbox'
) ON CONFLICT DO NOTHING;
