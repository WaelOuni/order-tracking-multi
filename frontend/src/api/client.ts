import type { OrderResponse, RegisterOrderRequest, UpdateOrderStatusRequest } from "../types";

const baseUrl = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";
const apiUser = import.meta.env.VITE_API_USER || "api-user";
const apiPassword = import.meta.env.VITE_API_PASSWORD || "change-me";

const defaultHeaders: HeadersInit = {
  "Content-Type": "application/json",
  Authorization: `Basic ${btoa(`${apiUser}:${apiPassword}`)}`
};

async function request<T>(path: string, init: RequestInit): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, init);
  if (!response.ok) {
    const text = await response.text();
    const message = text || response.statusText;
    throw new Error(`${response.status} ${message}`);
  }
  return response.json() as Promise<T>;
}

export function registerOrder(payload: RegisterOrderRequest) {
  return request<OrderResponse>("/api/orders", {
    method: "POST",
    headers: defaultHeaders,
    body: JSON.stringify(payload)
  });
}

export function getOrderById(orderId: string) {
  return request<OrderResponse>(`/api/orders/${orderId}`, {
    method: "GET",
    headers: defaultHeaders
  });
}

export function updateOrderStatus(orderId: string, payload: UpdateOrderStatusRequest) {
  return request<OrderResponse>(`/api/orders/${orderId}/status`, {
    method: "PUT",
    headers: defaultHeaders,
    body: JSON.stringify(payload)
  });
}

export function listOrders(params: {
  orderId?: string;
  customerId?: string;
  status?: string;
  updatedFrom?: string;
  updatedTo?: string;
  page?: number;
  size?: number;
  sortBy?: string;
  sortDir?: string;
}) {
  const search = new URLSearchParams();
  if (params.orderId) search.set("orderId", params.orderId);
  if (params.customerId) search.set("customerId", params.customerId);
  if (params.status) search.set("status", params.status);
  if (params.updatedFrom) search.set("updatedFrom", params.updatedFrom);
  if (params.updatedTo) search.set("updatedTo", params.updatedTo);
  if (params.page !== undefined) search.set("page", String(params.page));
  if (params.size) search.set("size", String(params.size));
  if (params.sortBy) search.set("sortBy", params.sortBy);
  if (params.sortDir) search.set("sortDir", params.sortDir);

  const query = search.toString();
  return request<OrderResponse[]>(`/api/orders${query ? `?${query}` : ""}`, {
    method: "GET",
    headers: defaultHeaders
  });
}
