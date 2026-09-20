export type DeliveryStatus = 'NEW' | 'ASSIGNED' | 'DELIVERED' | 'FAILED';
export function nextStatus(current: DeliveryStatus, action: 'ASSIGN' | 'DELIVER' | 'FAIL'): DeliveryStatus {
  if (current === 'NEW' && action === 'ASSIGN') return 'ASSIGNED';
  if (current === 'ASSIGNED' && action === 'DELIVER') return 'DELIVERED';
  if (current === 'ASSIGNED' && action === 'FAIL') return 'FAILED';
  throw new Error('INVALID_DELIVERY_TRANSITION');
}
