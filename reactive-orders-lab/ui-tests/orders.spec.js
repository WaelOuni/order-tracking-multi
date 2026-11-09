import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';

// Ces tests isolent l'UI : les réponses sont des fixtures, pas une preuve Kafka/Mongo.
// Le scénario @live dans live.spec.js complète cette couche avec la vraie stack.
const makeOrder = (overrides = {}) => ({
  id: randomUUID(), customerRef: 'CLIENT-001', sku: 'KEYBOARD', productName: 'Clavier',
  quantity: 1, unitPriceCents: 6990, totalCents: 6990, status: 'CREATED',
  createdAt: '2026-11-09T10:00:00Z', version: 0, ...overrides,
});

async function fixture(page, { orders = [], events = [], create, cancel, list } = {}) {
  const db = { orders: [...orders], createRequests: [], cancelRequests: [] };
  await page.route('**/api/events', (route) => route.fulfill({
    status: 200,
    contentType: 'text/event-stream',
    headers: { 'Cache-Control': 'no-cache' },
    // De vrais frames SSE traversent EventSource ; on ne remplace pas cette API.
    body: `event: snapshot\ndata: ${JSON.stringify(events)}\n\n`,
  }));
  await page.route('**/api/orders?*', async (route) => {
    if (list && await list(route, db)) return;
    const url = new URL(route.request().url());
    const currentPage = Number(url.searchParams.get('page'));
    const size = Number(url.searchParams.get('size'));
    await route.fulfill({ json: { items: db.orders.slice(currentPage * size, (currentPage + 1) * size), page: currentPage, size, hasNext: db.orders.length > (currentPage + 1) * size } });
  });
  await page.route('**/api/orders', async (route) => {
    const entry = { body: route.request().postDataJSON(), key: route.request().headers()['idempotency-key'] };
    db.createRequests.push(entry);
    if (create && await create(route, db, entry)) return;
    const order = makeOrder({ ...entry.body, id: entry.key, totalCents: 6990 * entry.body.quantity });
    db.orders.unshift(order);
    await route.fulfill({ status: 201, json: order });
  });
  await page.route('**/api/orders/*/cancel', async (route) => {
    const id = new URL(route.request().url()).pathname.split('/')[3];
    db.cancelRequests.push(id);
    if (cancel && await cancel(route, db, id)) return;
    const order = db.orders.find((item) => item.id === id);
    order.status = 'CANCELLED';
    order.version++;
    await route.fulfill({ json: order });
  });
  return db;
}

async function fillOrder(page, customer = 'CLIENT-001') {
  await page.getByLabel('Référence client', { exact: true }).fill(customer);
}

test('présente un état vide explicite et un formulaire accessible', async ({ page }) => {
  await fixture(page);
  await page.goto('/');
  await expect(page).toHaveTitle(/Reactive Orders Lab/);
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Une commande.');
  await expect(page.getByText('Le flux commence avec toi.')).toBeVisible();
  await expect(page.getByLabel('Produit', { exact: true })).toHaveValue('KEYBOARD');
  await expect(page.getByRole('button', { name: 'Suivante' })).toBeDisabled();
  await expect(page.locator('#events-empty')).toBeVisible();
});

test('indique le chargement sans annoncer une liste vide prématurément', async ({ page }) => {
  let release;
  const gate = new Promise((resolve) => { release = resolve; });
  await fixture(page, { list: async () => { await gate; return false; } });
  await page.goto('/');
  await expect(page.getByText('Chargement des commandes…')).toBeVisible();
  await expect(page.locator('#orders-empty')).toBeHidden();
  release();
  await expect(page.locator('#orders-empty')).toBeVisible();
  await expect(page.locator('#orders-section')).toHaveAttribute('aria-busy', 'false');
});

test('crée une commande et protège contre deux soumissions concurrentes', async ({ page }) => {
  let release;
  const gate = new Promise((resolve) => { release = resolve; });
  const db = await fixture(page, { create: async () => { await gate; return false; } });
  await page.goto('/');
  await fillOrder(page);
  await page.getByLabel('Quantité', { exact: true }).fill('2');
  await expect(page.locator('#estimate')).toHaveText(/139,80/);
  await page.getByRole('button', { name: 'Créer la commande' }).click();
  await expect(page.getByRole('button', { name: 'Création en cours…' })).toBeDisabled();
  await page.locator('#order-form').dispatchEvent('submit');
  await expect.poll(() => db.createRequests.length).toBe(1);
  expect(db.createRequests[0].key).toMatch(/^[0-9a-f]{8}-[0-9a-f-]{27}$/);
  expect(db.createRequests[0].body).toEqual({ customerRef: 'CLIENT-001', sku: 'KEYBOARD', quantity: 2 });
  release();
  await expect(page.locator('#orders-body tr')).toHaveCount(1);
  await expect(page.locator('#orders-body')).toContainText('139,80');
  await expect(page.locator('#notification')).toContainText('enregistrée');
});

test('réutilise la clé si la réponse de création est perdue, sans doublon', async ({ page }) => {
  const db = await fixture(page, {
    create: async (route, data, entry) => {
      if (data.createRequests.length === 1) {
        // Cas difficile : le serveur a persisté, mais le navigateur n'a pas reçu la réponse.
        data.orders.push(makeOrder({ id: entry.key }));
        await route.abort('failed');
      } else {
        await route.fulfill({ status: 200, json: data.orders[0] });
      }
      return true;
    },
  });
  await page.goto('/');
  await fillOrder(page);
  await page.getByRole('button', { name: 'Créer la commande' }).click();
  await expect(page.locator('#form-error')).toContainText('Connexion interrompue');
  await page.getByRole('button', { name: 'Créer la commande' }).click();
  await expect(page.locator('#orders-body tr')).toHaveCount(1);
  expect(db.createRequests).toHaveLength(2);
  expect(db.createRequests[0].key).toBe(db.createRequests[1].key);
  expect(db.orders).toHaveLength(1);
});

test('une intention modifiée reçoit une nouvelle clé après échec', async ({ page }) => {
  const db = await fixture(page, { create: async (route) => { await route.abort('failed'); return true; } });
  await page.goto('/');
  await fillOrder(page);
  await page.getByRole('button', { name: 'Créer la commande' }).click();
  await expect(page.locator('#form-error')).toBeVisible();
  await page.getByLabel('Quantité', { exact: true }).fill('2');
  await page.getByRole('button', { name: 'Créer la commande' }).click();
  await expect.poll(() => db.createRequests.length).toBe(2);
  expect(db.createRequests[0].key).not.toBe(db.createRequests[1].key);
});

test('un timeout libère le formulaire et conserve la clé d’une création incertaine', async ({ page }) => {
  let release;
  const gate = new Promise((resolve) => { release = resolve; });
  const db = await fixture(page, { create: async (route, data, entry) => {
    if (data.createRequests.length === 1) {
      data.orders.push(makeOrder({ id: entry.key }));
      await gate; // La commande existe déjà ; seule sa réponse reste en attente.
    }
    await route.fulfill({ status: 200, json: data.orders[0] });
    return true;
  } });
  await page.clock.install();
  await page.goto('/');
  await expect(page.locator('#orders-empty')).toBeVisible();
  await fillOrder(page);
  await page.getByRole('button', { name: 'Créer la commande' }).click();
  await expect.poll(() => db.createRequests.length).toBe(1);
  await page.clock.fastForward(15_001);
  await expect(page.locator('#form-error')).toContainText('trop de temps');
  await expect(page.locator('#form-error')).toContainText('clé de cette demande est conservée');
  await expect(page.getByRole('button', { name: 'Créer la commande' })).toBeEnabled();
  release();
  await page.getByRole('button', { name: 'Créer la commande' }).click();
  await expect(page.locator('#orders-body tr')).toHaveCount(1);
  expect(db.createRequests).toHaveLength(2);
  expect(db.createRequests[0].key).toBe(db.createRequests[1].key);
});

test('bloque les saisies invalides avant le réseau', async ({ page }) => {
  const db = await fixture(page);
  await page.goto('/');
  await page.getByRole('button', { name: 'Créer la commande' }).click();
  await expect(page.locator('#customer-ref:invalid')).toBeVisible();
  await fillOrder(page);
  await page.getByLabel('Quantité', { exact: true }).fill('0');
  await page.getByRole('button', { name: 'Créer la commande' }).click();
  await expect(page.locator('#quantity:invalid')).toBeVisible();
  expect(db.createRequests).toHaveLength(0);
  await page.getByLabel('Quantité', { exact: true }).fill('1');
  await fillOrder(page, '   ');
  await page.getByRole('button', { name: 'Créer la commande' }).click();
  await expect(page.locator('#form-error')).toContainText('espaces');
  expect(db.createRequests).toHaveLength(0);
});

test('affiche un ProblemDetail de conflit comme texte, sans injection HTML', async ({ page }) => {
  await fixture(page, { create: async (route) => {
    await route.fulfill({ status: 409, json: { status: 409, title: 'Conflict', detail: '<img src=x onerror=alert(1)> Clé déjà utilisée pour une autre demande.' } });
    return true;
  } });
  await page.goto('/');
  await fillOrder(page);
  await page.getByRole('button', { name: 'Créer la commande' }).click();
  await expect(page.locator('#form-error')).toContainText('<img src=x onerror=alert(1)>');
  await expect(page.locator('#form-error img')).toHaveCount(0);
  await expect(page.locator('#orders-body tr')).toHaveCount(0);
});

test('explique une indisponibilité du catalogue et permet de réessayer', async ({ page }) => {
  await fixture(page, { create: async (route) => {
    await route.fulfill({ status: 503, json: { status: 503, detail: 'Le catalogue est temporairement indisponible.' } });
    return true;
  } });
  await page.goto('/');
  await fillOrder(page);
  await page.getByRole('button', { name: 'Créer la commande' }).click();
  await expect(page.locator('#form-error')).toContainText('catalogue');
  await expect(page.getByRole('button', { name: 'Créer la commande' })).toBeEnabled();
});

test('pagine des réponses bornées et revient à la page précédente', async ({ page }) => {
  await fixture(page, { orders: Array.from({ length: 21 }, (_, index) => makeOrder({ customerRef: `CLIENT-${String(index).padStart(2, '0')}` })) });
  await page.goto('/');
  await expect(page.locator('#orders-body tr')).toHaveCount(10);
  await expect(page.locator('#orders-body')).toContainText('CLIENT-00');
  await page.getByRole('button', { name: 'Suivante' }).click();
  await expect(page.locator('#page-label')).toHaveText('Page 2');
  await expect(page.locator('#orders-body')).toContainText('CLIENT-10');
  await page.getByRole('button', { name: 'Suivante' }).click();
  await expect(page.locator('#orders-body tr')).toHaveCount(1);
  await expect(page.getByRole('button', { name: 'Suivante' })).toBeDisabled();
  await page.getByRole('button', { name: 'Précédente' }).click();
  await expect(page.locator('#page-label')).toHaveText('Page 2');
  await expect(page.locator('#orders-body tr')).toHaveCount(10);
});

test('une page refusée ne renumérote pas les lignes de la page précédente', async ({ page }) => {
  await fixture(page, {
    orders: Array.from({ length: 12 }, (_, index) => makeOrder({ customerRef: `CLIENT-${String(index).padStart(2, '0')}` })),
    list: async (route) => {
      if (new URL(route.request().url()).searchParams.get('page') !== '1') return false;
      await route.fulfill({ status: 503, json: { status: 503, detail: 'Lecture indisponible.' } });
      return true;
    },
  });
  await page.goto('/');
  await expect(page.locator('#orders-body tr')).toHaveCount(10);
  await page.getByRole('button', { name: 'Suivante' }).click();
  await expect(page.locator('#orders-error')).toContainText('indisponible');
  await expect(page.locator('#page-label')).toHaveText('Page 1');
  await expect(page.locator('#orders-body')).toContainText('CLIENT-00');
  await expect(page.getByRole('button', { name: 'Précédente' })).toBeDisabled();
});

test('annule une commande puis affiche l’état confirmé par le serveur', async ({ page }) => {
  const order = makeOrder();
  const db = await fixture(page, { orders: [order] });
  await page.goto('/');
  await page.getByRole('button', { name: /^Annuler la commande/ }).click();
  await expect(page.locator('.status-badge')).toHaveText('Annulée');
  await expect(page.getByRole('button', { name: /^Annuler la commande/ })).toBeDisabled();
  await expect(page.locator('#notification')).toContainText('annulée');
  expect(db.cancelRequests).toEqual([order.id]);
});

test('une erreur d’annulation conserve le statut réellement enregistré', async ({ page }) => {
  await fixture(page, { orders: [makeOrder()], cancel: async (route) => {
    await route.fulfill({ status: 409, json: { status: 409, detail: 'La version a changé. Actualise la commande.' } });
    return true;
  } });
  await page.goto('/');
  await page.getByRole('button', { name: /^Annuler la commande/ }).click();
  await expect(page.locator('#notification')).toContainText('version a changé');
  await expect(page.locator('.status-badge')).toHaveText('Créée');
  await expect(page.getByRole('button', { name: /^Annuler la commande/ })).toBeEnabled();
});

test('consomme de vrais frames SSE et borne le nombre d’événements affichés', async ({ page }) => {
  const events = Array.from({ length: 22 }, (_, index) => ({ eventId: randomUUID(), orderId: randomUUID(), type: index % 2 ? 'ORDER_CANCELLED' : 'ORDER_CREATED', sku: 'KEYBOARD', quantity: 1, recordedAt: '2026-11-09T10:00:00Z' }));
  await fixture(page, { events });
  await page.goto('/');
  await expect(page.locator('#events-list li')).toHaveCount(20);
  await expect(page.locator('#events-empty')).toBeHidden();
  await expect(page.locator('#events-list li').first()).toContainText('Commande créée');
  await expect(page.locator('#events-list li').nth(1)).toContainText('Commande annulée');
  await expect(page.locator('#events-list li').first()).toHaveAttribute('data-event-id', events[0].eventId);
});

test('une panne de lecture est distincte d’une liste vide et peut être récupérée', async ({ page }) => {
  let unavailable = true;
  await fixture(page, { list: async (route) => {
    if (!unavailable) return false;
    await route.fulfill({ status: 503, json: { status: 503, detail: 'Service temporairement indisponible.' } });
    return true;
  } });
  await page.goto('/');
  await expect(page.locator('#orders-error')).toContainText('indisponible');
  await expect(page.locator('#orders-empty')).toBeHidden();
  unavailable = false;
  await page.getByRole('button', { name: 'Actualiser les commandes' }).click();
  await expect(page.locator('#orders-empty')).toBeVisible();
  await expect(page.locator('#orders-error')).toBeHidden();
});

test('reste utilisable sur mobile et rend les références sans interpréter le HTML', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await fixture(page, { orders: [makeOrder({ customerRef: '<script>alert(1)</script>' })] });
  await page.goto('/');
  await expect(page.locator('.customer-ref')).toHaveText('<script>alert(1)</script>');
  await expect(page.getByRole('button', { name: 'Créer la commande' })).toBeVisible();
  const fits = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
  if (!fits) {
    // Le diagnostic complète l'assertion ; il ne masque ni n'ignore un débordement.
    const layout = await page.evaluate(() => ({
      viewport: window.innerWidth,
      documentWidth: document.documentElement.scrollWidth,
      overflow: [...document.querySelectorAll('body *')].flatMap((element) => {
        const rect = element.getBoundingClientRect();
        return rect.right > window.innerWidth + 0.5 && rect.width > 0
          ? [{ tag: element.tagName, id: element.id, class: element.className, left: rect.left, right: rect.right,
            width: rect.width, position: getComputedStyle(element).position,
            container: element.offsetParent?.id || element.offsetParent?.className || element.offsetParent?.tagName }]
          : [];
      }),
    }));
    console.log('Mobile overflow diagnosis:', JSON.stringify(layout));
    await test.info().attach('mobile-layout.json', { body: JSON.stringify(layout, null, 2), contentType: 'application/json' });
    await test.info().attach('mobile-full-page.png', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
  }
  expect(fits).toBe(true);
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: 'Aller aux commandes' })).toBeFocused();
});
