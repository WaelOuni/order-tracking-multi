export type OrderStatus =
  | "CREATED"
  | "CANCELLED"
  | "SHIPPED"
  | "DELIVERED"
  | "PACKED";

export type TrackingEvent = {
  status: string;
  occurredAt: string;
  note?: string | null;
};

export type OrderResponse = {
  id: string;
  customerId: string;
  status: OrderStatus | string;
  createdAt: string;
  updatedAt: string;
  history: TrackingEvent[];
};

export type RegisterOrderRequest = {
  orderId: string;
  customerId: string;
};

export type UpdateOrderStatusRequest = {
  status: OrderStatus;
  note?: string | null;
};
