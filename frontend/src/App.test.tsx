import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import { getOrderById, listOrders, registerOrder, updateOrderStatus } from "./api/client";
import type { OrderResponse } from "./types";

vi.mock("./api/client", () => ({
  getOrderById: vi.fn(),
  listOrders: vi.fn(),
  registerOrder: vi.fn(),
  updateOrderStatus: vi.fn()
}));

const order = (overrides: Partial<OrderResponse> = {}): OrderResponse => ({
  id: "o-1001",
  customerId: "c-2001",
  status: "CREATED",
  createdAt: "2026-04-29T10:00:00.000Z",
  updatedAt: "2026-04-29T10:00:00.000Z",
  history: [
    {
      status: "CREATED",
      occurredAt: "2026-04-29T10:00:00.000Z",
      note: "Registered"
    }
  ],
  ...overrides
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("App", () => {
  it("validates required register fields before calling the API", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "Register" }));

    expect(await screen.findByText("Order ID and Customer ID are required.")).toBeInTheDocument();
    expect(registerOrder).not.toHaveBeenCalled();
  });

  it("registers an order and updates the session snapshot", async () => {
    vi.mocked(registerOrder).mockResolvedValueOnce(order());
    const user = userEvent.setup();
    render(<App />);

    await user.type(screen.getByLabelText("Order ID", { selector: "#register-order-id" }), "o-1001");
    await user.type(screen.getByLabelText("Customer ID", { selector: "#register-customer-id" }), "c-2001");
    await user.click(screen.getByRole("button", { name: "Register" }));

    expect(registerOrder).toHaveBeenCalledWith({ orderId: "o-1001", customerId: "c-2001" });
    expect(await screen.findByText("Order created")).toBeInTheDocument();
    expect(screen.getByText("Created order")).toBeInTheDocument();
    expect(screen.getAllByText("o-1001").length).toBeGreaterThan(0);
  });

  it("tracks an order and renders its history", async () => {
    vi.mocked(getOrderById).mockResolvedValueOnce(order({ status: "SHIPPED" }));
    const user = userEvent.setup();
    render(<App />);

    await user.type(screen.getByLabelText("Order ID", { selector: "#track-order-id" }), "o-1001");
    await user.click(screen.getByRole("button", { name: "Track" }));

    expect(getOrderById).toHaveBeenCalledWith("o-1001");
    expect(await screen.findByText("Order tracked")).toBeInTheDocument();
    expect(screen.getByText("Tracked order")).toBeInTheDocument();
    expect(screen.getByText("Registered")).toBeInTheDocument();
  });

  it("updates an order status with a null note when the note field is empty", async () => {
    vi.mocked(updateOrderStatus).mockResolvedValueOnce(order({ status: "PACKED" }));
    const user = userEvent.setup();
    render(<App />);

    await user.type(screen.getByLabelText("Order ID", { selector: "#update-order-id" }), "o-1001");
    await user.selectOptions(screen.getByLabelText("Status", { selector: "#update-status" }), "PACKED");
    await user.click(screen.getByRole("button", { name: "Update" }));

    expect(updateOrderStatus).toHaveBeenCalledWith("o-1001", { status: "PACKED", note: null });
    expect(await screen.findByText("Order updated")).toBeInTheDocument();
    expect(screen.getByText("Updated order")).toBeInTheDocument();
  });

  it("lists filtered orders and renders the result table", async () => {
    vi.mocked(listOrders).mockResolvedValueOnce([order({ status: "DELIVERED" })]);
    const user = userEvent.setup();
    render(<App />);

    await user.type(screen.getByLabelText("Order ID", { selector: "#list-order-id" }), "o-1001");
    await user.type(screen.getByLabelText("Customer ID", { selector: "#list-customer-id" }), "c-2001");
    await user.selectOptions(screen.getByLabelText("Status", { selector: "#list-status" }), "DELIVERED");
    await user.click(screen.getByRole("button", { name: "Fetch Orders" }));

    expect(listOrders).toHaveBeenCalledWith(expect.objectContaining({
      orderId: "o-1001",
      customerId: "c-2001",
      status: "DELIVERED",
      page: 0,
      size: 25,
      sortBy: "updatedAt",
      sortDir: "desc"
    }));

    const allOrdersSection = screen.getByText("All Orders").closest("section")!;
    expect(await within(allOrdersSection).findByText("DELIVERED", { selector: ".status-chip" })).toBeInTheDocument();
    expect(within(allOrdersSection).getByText("c-2001")).toBeInTheDocument();
  });
});
