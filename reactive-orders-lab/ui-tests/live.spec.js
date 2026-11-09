import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';

test('@live création → outbox → Kafka → audit SSE → annulation', async ({ page }) => {
  // Aucun page.route ici : HTTP, Mongo, relay outbox, Kafka et SSE sont réellement sollicités.
  const customer = `E2E-${randomUUID().slice(0, 8)}`;
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto('/');
  await page.getByLabel('Référence client', { exact: true }).fill(customer);
  await page.getByLabel('Produit', { exact: true }).selectOption('MOUSE');
  await page.getByLabel('Quantité', { exact: true }).fill('2');
  await page.getByRole('button', { name: 'Créer la commande' }).click();
  const row = page.locator('#orders-body tr').filter({ hasText: customer });
  await expect(row).toBeVisible();
  await expect(row.locator('.status-badge')).toHaveText('Créée');
  await expect(row.locator('.order-total')).toContainText('59,80');
  const id = await row.getAttribute('data-order-id');
  await expect(page.locator(`#events-list li[data-order-id="${id}"][data-event-type="ORDER_CREATED"]`)).toBeVisible();
  await row.getByRole('button', { name: /^Annuler la commande/ }).click();
  await expect(row.locator('.status-badge')).toHaveText('Annulée');
  await expect(page.locator(`#events-list li[data-order-id="${id}"][data-event-type="ORDER_CANCELLED"]`)).toBeVisible();
  expect(errors).toEqual([]);
});
