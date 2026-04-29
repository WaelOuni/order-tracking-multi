import { expect, test } from "@playwright/test";

const order = {
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
  ]
};

test.beforeEach(async ({ page }) => {
  await page.route((url) => url.pathname === "/api/orders", async (route) => {
    const request = route.request();
    if (request.method() === "POST") {
      await route.fulfill({ json: order });
      return;
    }

    await route.fulfill({ json: [{ ...order, status: "DELIVERED" }] });
  });

  await page.route((url) => /^\/api\/orders\/[^/]+\/status$/.test(url.pathname), async (route) => {
    await route.fulfill({
      json: {
        ...order,
        status: "PACKED",
        updatedAt: "2026-04-29T11:00:00.000Z",
        history: [
          ...order.history,
          { status: "PACKED", occurredAt: "2026-04-29T11:00:00.000Z", note: "Packed" }
        ]
      }
    });
  });

  await page.route((url) => /^\/api\/orders\/[^/]+$/.test(url.pathname), async (route) => {
    await route.fulfill({
      json: {
        ...order,
        status: "SHIPPED",
        updatedAt: "2026-04-29T12:00:00.000Z",
        history: [
          ...order.history,
          { status: "SHIPPED", occurredAt: "2026-04-29T12:00:00.000Z", note: "Shipped" }
        ]
      }
    });
  });
});

test("registers an order and shows the latest action", async ({ page }) => {
  await page.goto("/");

  await page.locator("#register-order-id").fill("o-1001");
  await page.locator("#register-customer-id").fill("c-2001");
  await page.getByRole("button", { name: "Register" }).click();

  await expect(page.getByText("Order created")).toBeVisible();
  await expect(page.getByText("Created order")).toBeVisible();
  await expect(page.locator(".action-banner .status", { hasText: "CREATED" })).toBeVisible();
});

test("tracks and updates an order through mocked backend calls", async ({ page }) => {
  await page.goto("/");

  await page.locator("#track-order-id").fill("o-1001");
  await page.getByRole("button", { name: "Track" }).click();
  await expect(page.getByText("Order tracked")).toBeVisible();
  await expect(page.locator(".history .note", { hasText: "Shipped" })).toBeVisible();

  await page.locator("#update-order-id").fill("o-1001");
  await page.locator("#update-note").fill("Packed");
  await page.getByRole("button", { name: "Update" }).click();

  await expect(page.getByText("Order updated")).toBeVisible();
  await expect(page.getByText("Updated order")).toBeVisible();
  await expect(page.locator(".action-banner .status", { hasText: "PACKED" })).toBeVisible();
});

test("lists orders with filters", async ({ page }) => {
  await page.goto("/");

  await page.locator("#list-order-id").fill("o-1001");
  await page.locator("#list-customer-id").fill("c-2001");
  await page.locator("#list-status").selectOption("DELIVERED");
  await page.getByRole("button", { name: "Fetch Orders" }).click();

  await expect(page.locator(".orders-table .status-chip", { hasText: "DELIVERED" })).toBeVisible();
  await expect(page.locator(".orders-row .mono", { hasText: "c-2001" })).toBeVisible();
});
