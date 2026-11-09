/*
 * Ici « réactif côté UI » signifie réagir aux réponses HTTP et au flux SSE.
 * Ce navigateur n'exécute pas Reactor : Mono/Flux vivent dans le backend Java.
 * EventSource gère la reconnexion ; chaque message snapshot remplace l'aperçu
 * autoritatif des 20 derniers audits. Ce flux ne remplace pas un journal exhaustif.
 */
const $ = (selector) => document.querySelector(selector);
const money = new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' });
const time = new Intl.DateTimeFormat('fr-FR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
const prices = { KEYBOARD: 6990, MOUSE: 2990, SCREEN: 24900 };
const state = {
  page: 0,
  loadedPage: 0,
  size: 10,
  hasNext: false,
  loadingOrders: false,
  orders: [],
  creating: false,
  cancelling: new Set(),
  pendingRequest: null,
  ordersController: null,
  mutationControllers: new Set(),
  requestSequence: 0,
  eventSource: null,
  snapshotSignature: null,
  refreshTimer: null,
};

class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

/** Un helper unique traduit le ProblemDetail HTTP sans injecter son texte en HTML. */
async function request(path, options = {}) {
  const controller = new AbortController();
  const parentSignal = options.signal;
  const forwardAbort = () => controller.abort();
  if (parentSignal?.aborted) controller.abort();
  else parentSignal?.addEventListener('abort', forwardAbort, { once: true });
  let timedOut = false;
  // Un formulaire ne doit pas rester bloqué indéfiniment si le réseau ne répond plus.
  // Annuler l'attente ne prouve PAS que le serveur a annulé sa transaction.
  const deadline = setTimeout(() => { timedOut = true; controller.abort(); }, 15_000);
  try {
    // fetch est asynchrone ; aucun blocage du thread d'interface pendant les E/S.
    const response = await fetch(path, {
      ...options,
      signal: controller.signal,
      headers: { Accept: 'application/json', ...options.headers },
    });
    if (!response.ok) {
      const problem = await response.json().catch((error) => {
        if (error.name === 'AbortError') throw error;
        return {};
      });
      const fallback = {
        400: 'Vérifie les informations saisies.',
        404: 'Cette commande ou ce produit est introuvable.',
        409: 'Cette demande entre en conflit avec une opération déjà effectuée.',
        422: 'Ce produit est indisponible.',
        503: 'Le catalogue ou un service est temporairement indisponible. Réessaie dans un instant.',
      }[response.status] || 'La demande a échoué. Réessaie dans un instant.';
      throw new ApiError(typeof problem.detail === 'string' ? problem.detail : fallback, response.status);
    }
    // Le délai couvre aussi la lecture du corps, qui peut se couper après les en-têtes.
    return response.status === 204 ? null : await response.json();
  } catch (error) {
    if (timedOut) throw new ApiError('Le serveur met trop de temps à répondre. Réessaie avec les mêmes informations.', 0);
    // AbortError signifie une requête volontairement remplacée ou une page fermée.
    if (error.name === 'AbortError') throw error;
    if (error instanceof ApiError) throw error;
    if (error instanceof SyntaxError) throw new ApiError('Réponse du serveur illisible. Réessaie avec les mêmes informations.', 0);
    throw new ApiError('Connexion interrompue. Réessaie dans un instant.', 0);
  } finally {
    clearTimeout(deadline);
    parentSignal?.removeEventListener('abort', forwardAbort);
  }
}

function showNotification(message, isError = false) {
  const notification = $('#notification');
  notification.textContent = message; // textContent protège des données interprétées comme HTML.
  notification.classList.toggle('error', isError);
  notification.hidden = false;
}

function updateEstimate() {
  const price = prices[$('#sku').value];
  const quantity = Number($('#quantity').value);
  $('#estimate').textContent = price && Number.isInteger(quantity) && quantity > 0
    ? money.format(price * quantity / 100)
    : '—';
}

function formPayload() {
  return {
    customerRef: $('#customer-ref').value.trim(),
    sku: $('#sku').value,
    quantity: Number($('#quantity').value),
  };
}

function setCreating(busy) {
  state.creating = busy;
  $('#order-fields').disabled = busy;
  $('#create-order').setAttribute('aria-busy', String(busy));
  $('#create-order .button-label').textContent = busy ? 'Création en cours…' : 'Créer la commande';
}

async function createOrder(event) {
  event.preventDefault();
  if (state.creating || !$('#order-form').reportValidity()) return;
  const payload = formPayload();
  if (!payload.customerRef) {
    $('#form-error').textContent = 'Saisis une référence client, sans te limiter à des espaces.';
    $('#form-error').hidden = false;
    $('#customer-ref').focus();
    return;
  }
  const signature = JSON.stringify(payload);
  // Un retry du même payload garde sa clé : une réponse perdue ne crée pas de doublon.
  // La clé change après succès ou modification du payload, jamais à chaque tentative.
  if (state.pendingRequest?.signature !== signature) {
    state.pendingRequest = { signature, key: crypto.randomUUID() };
  }
  const controller = new AbortController();
  state.mutationControllers.add(controller);
  setCreating(true);
  $('#form-error').hidden = true;
  try {
    const order = await request('/api/orders', {
      method: 'POST',
      signal: controller.signal,
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': state.pendingRequest.key },
      body: JSON.stringify(payload),
    });
    if (!order || typeof order.id !== 'string')
      throw new ApiError('Confirmation du serveur illisible. Réessaie avec les mêmes informations.', 0);
    state.pendingRequest = null; // Seulement une réponse réussie confirme la fin de cette intention.
    showNotification(`Commande ${order.id.slice(0, 8)} enregistrée. Son événement arrivera dans le flux.`);
    state.page = 0;
    await loadOrders();
  } catch (error) {
    if (error.name !== 'AbortError') {
      $('#form-error').textContent = error.message + (error.status === 0
        ? ' La clé de cette demande est conservée tant que tu ne modifies pas le formulaire et ne recharges pas la page.'
        : '');
      $('#form-error').hidden = false;
    }
  } finally {
    state.mutationControllers.delete(controller);
    setCreating(false);
  }
}

function renderOrders() {
  const fragment = document.createDocumentFragment();
  for (const order of state.orders) {
    const row = $('#order-row-template').content.cloneNode(true);
    row.querySelector('tr').dataset.orderId = order.id;
    row.querySelector('.order-id').textContent = `#${order.id.slice(0, 8)}`;
    row.querySelector('.order-id').title = order.id;
    row.querySelector('.customer-ref').textContent = order.customerRef;
    row.querySelector('.product-name').textContent = order.productName || order.sku;
    row.querySelector('.order-quantity').textContent = `${order.quantity} × ${money.format(order.unitPriceCents / 100)}`;
    row.querySelector('.order-total').textContent = money.format(order.totalCents / 100);
    const cancelled = order.status === 'CANCELLED';
    const status = row.querySelector('.status-badge');
    status.textContent = cancelled ? 'Annulée' : 'Créée';
    status.classList.toggle('cancelled', cancelled);
    const cancel = row.querySelector('.cancel-button');
    cancel.textContent = state.cancelling.has(order.id) ? 'Annulation…' : 'Annuler';
    cancel.disabled = cancelled || state.cancelling.has(order.id);
    cancel.setAttribute('aria-label', `Annuler la commande ${order.id.slice(0, 8)}`);
    cancel.addEventListener('click', () => cancelOrder(order.id));
    fragment.append(row);
  }
  $('#orders-body').replaceChildren(fragment);
  $('#orders-table-wrapper').hidden = state.orders.length === 0;
  $('#orders-empty').hidden = state.loadingOrders || !$('#orders-error').hidden || state.orders.length !== 0;
  $('#previous-page').disabled = state.loadingOrders || state.page === 0;
  $('#next-page').disabled = state.loadingOrders || !state.hasNext;
  $('#page-label').textContent = `Page ${state.page + 1}`;
}

async function loadOrders() {
  // Une navigation rapide annule la requête précédente : sa réponse ne doit pas
  // écraser la page plus récente. L'annulation peut aussi remonter au Flux backend.
  state.ordersController?.abort();
  const controller = new AbortController();
  state.ordersController = controller;
  const sequence = ++state.requestSequence;
  state.loadingOrders = true;
  $('#orders-section').setAttribute('aria-busy', 'true');
  $('#orders-loading').hidden = state.orders.length > 0;
  $('#orders-empty').hidden = true;
  $('#orders-error').hidden = true;
  $('#previous-page').disabled = true;
  $('#next-page').disabled = true;
  try {
    const result = await request(`/api/orders?page=${state.page}&size=${state.size}`, { signal: controller.signal });
    // Garde complémentaire : même un transport qui ignore l'annulation ne gagne pas la course.
    if (sequence !== state.requestSequence) return;
    if (!result || !Array.isArray(result.items) || !Number.isInteger(result.page) || typeof result.hasNext !== 'boolean'
      || result.items.some((order) => !order || typeof order.id !== 'string' || !['CREATED', 'CANCELLED'].includes(order.status)))
      throw new ApiError('Réponse de pagination invalide. Actualise les commandes.', 0);
    state.orders = result.items;
    state.page = result.page;
    state.loadedPage = result.page;
    state.hasNext = result.hasNext;
  } catch (error) {
    if (error.name === 'AbortError' || sequence !== state.requestSequence) return;
    state.page = state.loadedPage; // Une page refusée ne renumérote pas les anciennes lignes.
    $('#orders-error').textContent = error.message;
    $('#orders-error').hidden = false;
  } finally {
    if (sequence === state.requestSequence) {
      state.loadingOrders = false;
      $('#orders-section').setAttribute('aria-busy', 'false');
      $('#orders-loading').hidden = true;
      renderOrders();
      // Une panne ne doit pas être présentée comme la preuve d'une liste vide.
      if (!$('#orders-error').hidden && state.orders.length === 0) $('#orders-empty').hidden = true;
    }
  }
}

async function cancelOrder(id) {
  if (state.cancelling.has(id)) return;
  state.cancelling.add(id);
  renderOrders();
  const controller = new AbortController();
  state.mutationControllers.add(controller);
  try {
    await request(`/api/orders/${encodeURIComponent(id)}/cancel`, {
      method: 'POST',
      signal: controller.signal,
      // JSON force un preflight cross-origin ; le backend n'autorise que l'origine locale.
      headers: { 'Content-Type': 'application/json' },
    });
    showNotification(`Commande ${id.slice(0, 8)} annulée. L’événement d’annulation est en cours de diffusion.`);
    await loadOrders(); // L'état affiché provient du serveur ; pas de succès optimiste mensonger.
  } catch (error) {
    if (error.name !== 'AbortError') showNotification(error.message, true);
  } finally {
    state.mutationControllers.delete(controller);
    state.cancelling.delete(id);
    renderOrders();
  }
}

function streamStatus(label, status = '') {
  $('#stream-label').textContent = label;
  $('#stream-status').className = `stream-status ${status}`;
}

function renderEvents(events) {
  const fragment = document.createDocumentFragment();
  // La limite est aussi appliquée côté serveur ; cette borne protège le DOM côté client.
  for (const event of events.slice(0, 20)) {
    const item = $('#event-template').content.cloneNode(true);
    const cancelled = event.type === 'ORDER_CANCELLED';
    item.querySelector('li').classList.toggle('cancelled', cancelled);
    item.querySelector('li').dataset.eventId = event.eventId;
    item.querySelector('li').dataset.orderId = event.orderId;
    item.querySelector('li').dataset.eventType = event.type;
    item.querySelector('.event-type').textContent = cancelled ? 'Commande annulée' : 'Commande créée';
    item.querySelector('.event-details').textContent = `#${event.orderId.slice(0, 8)} · ${event.sku} · quantité ${event.quantity}`;
    const date = new Date(event.recordedAt || event.occurredAt);
    const timestamp = item.querySelector('time');
    if (!Number.isNaN(date.getTime())) {
      timestamp.dateTime = date.toISOString();
      timestamp.textContent = time.format(date);
      timestamp.title = date.toLocaleString('fr-FR');
    }
    fragment.append(item);
  }
  // Un snapshot remplace l'aperçu : les reconnects ne dupliquent pas les lignes.
  $('#events-list').replaceChildren(fragment);
  $('#events-empty').hidden = events.length > 0;
}

function connectEvents() {
  if (state.eventSource) return; // Une seule souscription par page, pas un listener par commande.
  const source = new EventSource('/api/events');
  state.eventSource = source;
  source.onopen = () => streamStatus('En direct', 'connected');
  source.onerror = () => streamStatus('Reconnexion automatique…', 'reconnecting');
  source.addEventListener('snapshot', (message) => {
    let events;
    try {
      events = JSON.parse(message.data);
      if (!Array.isArray(events) || events.some((event) => !event ||
        typeof event.eventId !== 'string' || typeof event.orderId !== 'string' ||
        !['ORDER_CREATED', 'ORDER_CANCELLED'].includes(event.type))) return;
    } catch {
      streamStatus('Événement illisible', 'reconnecting');
      return;
    }
    const signature = JSON.stringify(events.map((event) => event.eventId));
    if (signature === state.snapshotSignature) return; // Pas de rendu inutile sur heartbeat identique.
    const previous = state.snapshotSignature;
    state.snapshotSignature = signature;
    renderEvents(events);
    // Une autre fenêtre peut avoir créé/annulé une commande ; le GET reste la source d'état.
    if (previous !== null || events.length > 0) {
      clearTimeout(state.refreshTimer);
      state.refreshTimer = setTimeout(() => loadOrders(), 100);
    }
  });
}

function cleanUp() {
  // Fermer le SSE libère la connexion et annule la souscription Flux côté WebFlux.
  state.eventSource?.close();
  state.eventSource = null;
  state.ordersController?.abort();
  for (const controller of state.mutationControllers) controller.abort();
  clearTimeout(state.refreshTimer);
}

$('#order-form').addEventListener('submit', createOrder);
$('#order-form').addEventListener('input', () => {
  updateEstimate();
  if (state.pendingRequest?.signature !== JSON.stringify(formPayload())) state.pendingRequest = null;
});
$('#refresh-orders').addEventListener('click', () => loadOrders());
$('#previous-page').addEventListener('click', () => { if (state.page > 0) { state.page--; loadOrders(); } });
$('#next-page').addEventListener('click', () => { if (state.hasNext) { state.page++; loadOrders(); } });
window.addEventListener('pagehide', cleanUp);
window.addEventListener('pageshow', (event) => {
  // Le cache de navigation peut restaurer la page sans rejouer le module JavaScript.
  if (event.persisted) { loadOrders(); connectEvents(); }
});
updateEstimate();
loadOrders();
connectEvents();
