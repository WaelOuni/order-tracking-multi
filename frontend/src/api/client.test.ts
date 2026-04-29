import { afterEach, describe, expect, it, vi } from "vitest";
import { getOrderById, listOrders, registerOrder, updateOrderStatus } from "./client";

const fetchMock = vi.fn();
const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || "";

afterEach(() => {
  vi.unstubAllGlobals();
  fetchMock.mockReset();
});

describe("api client", () => {
  it("registers an order with JSON and basic auth headers", async () => {
    fetchMock.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ id: "o-1001", customerId: "c-2001", status: "CREATED", history: [] })
    });
    vi.stubGlobal("fetch", fetchMock);

    await expect(registerOrder({ orderId: "o-1001", customerId: "c-2001" })).resolves.toMatchObject({
      id: "o-1001",
      status: "CREATED"
    });

    expect(fetchMock).toHaveBeenCalledWith(`${apiBaseUrl}/api/orders`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Basic ${btoa("api-user:change-me")}`
      },
      body: JSON.stringify({ orderId: "o-1001", customerId: "c-2001" })
    });
  });

  it("fetches an order by id", async () => {
    fetchMock.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ id: "o-1001", customerId: "c-2001", status: "SHIPPED", history: [] })
    });
    vi.stubGlobal("fetch", fetchMock);

    await getOrderById("o-1001");

    expect(fetchMock).toHaveBeenCalledWith(`${apiBaseUrl}/api/orders/o-1001`, {
      method: "GET",
      headers: expect.objectContaining({
        Authorization: `Basic ${btoa("api-user:change-me")}`
      })
    });
  });

  it("updates an order status with a nullable note", async () => {
    fetchMock.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ id: "o-1001", customerId: "c-2001", status: "PACKED", history: [] })
    });
    vi.stubGlobal("fetch", fetchMock);

    await updateOrderStatus("o-1001", { status: "PACKED", note: null });

    expect(fetchMock).toHaveBeenCalledWith(`${apiBaseUrl}/api/orders/o-1001/status`, {
      method: "PUT",
      headers: expect.any(Object),
      body: JSON.stringify({ status: "PACKED", note: null })
    });
  });

  it("serializes list filters into the query string", async () => {
    fetchMock.mockResolvedValueOnce({
      ok: true,
      json: async () => []
    });
    vi.stubGlobal("fetch", fetchMock);

    await listOrders({
      orderId: "o-1001",
      customerId: "c-2001",
      status: "DELIVERED",
      updatedFrom: "2026-04-01T00:00:00.000Z",
      updatedTo: "2026-04-02T00:00:00.000Z",
      page: 2,
      size: 50,
      sortBy: "createdAt",
      sortDir: "asc"
    });

    expect(fetchMock).toHaveBeenCalledWith(
      `${apiBaseUrl}/api/orders?orderId=o-1001&customerId=c-2001&status=DELIVERED&updatedFrom=2026-04-01T00%3A00%3A00.000Z&updatedTo=2026-04-02T00%3A00%3A00.000Z&page=2&size=50&sortBy=createdAt&sortDir=asc`,
      {
        method: "GET",
        headers: expect.any(Object)
      }
    );
  });

  it("throws a useful error when the API rejects a request", async () => {
    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 409,
      statusText: "Conflict",
      text: async () => "Invalid transition"
    });
    vi.stubGlobal("fetch", fetchMock);

    await expect(getOrderById("missing")).rejects.toThrow("409 Invalid transition");
  });
});
