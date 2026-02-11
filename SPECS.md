# Order Tracking – Functional Specs (QA)

This document lists the functional use cases that must be tested end-to-end.  
System under test: Spring Boot API + React UI.

## Environments

- Backend base URL: `http://localhost:8080`
- Frontend base URL: `http://localhost:5173`
- Auth: HTTP Basic (use configured `APP_SECURITY_USER` / `APP_SECURITY_PASSWORD`)
- MongoDB: configured via `MONGODB_URI`
- Kafka: configured via `KAFKA_*` env vars (optional for testing UI flows)

## API Endpoints

- `POST /api/orders`
- `GET /api/orders/{id}`
- `PUT /api/orders/{id}/status`
- `GET /api/orders` (filters + pagination + sorting)

## Status Values

Valid statuses:
- `CREATED`
- `PACKED`
- `SHIPPED`
- `DELIVERED`
- `CANCELLED`

Invalid status example: `UPDATED`

## Use Cases (API)

### UC-API-01 Register Order (Happy Path)
**Given** a new `orderId` and `customerId`  
**When** `POST /api/orders`  
**Then** response status `201` and body contains:
- `id == orderId`
- `customerId == customerId`
- `status == CREATED`
- non-null `createdAt`, `updatedAt`

### UC-API-02 Register Order (Duplicate)
**Given** an existing `orderId`  
**When** `POST /api/orders` with same `orderId`  
**Then** error response with message `"Order already exists: {orderId}"`

### UC-API-03 Track Order (Happy Path)
**Given** an existing `orderId`  
**When** `GET /api/orders/{id}`  
**Then** status `200` with full order data.

### UC-API-04 Track Order (Not Found)
**When** `GET /api/orders/{id}` for missing order  
**Then** status `404` with problem response.

### UC-API-05 Update Status (Happy Path)
**Given** an existing order  
**When** `PUT /api/orders/{id}/status` with valid next status  
**Then** status `200` and body reflects new status + updated history.

### UC-API-06 Update Status (Invalid Status)
**When** `PUT /api/orders/{id}/status` with invalid status (e.g. `UPDATED`)  
**Then** status `400` and message about invalid enum value.

### UC-API-07 Update Status (Invalid Transition)
**Given** invalid transition (e.g. `CREATED -> DELIVERED`)  
**When** `PUT /api/orders/{id}/status`  
**Then** status `409` and business rule violation message.

### UC-API-08 List Orders (Basic)
**When** `GET /api/orders`  
**Then** status `200` and returns a list (default size <= 50).

### UC-API-09 List Orders (Filters)
**When** `GET /api/orders?orderId=o-1`  
**Then** all returned orders contain `o-1` in ID.

**When** `GET /api/orders?customerId=c-1`  
**Then** all returned orders contain `c-1` in customerId.

**When** `GET /api/orders?status=SHIPPED`  
**Then** all returned orders have status `SHIPPED`.

### UC-API-10 List Orders (Pagination)
**When** `GET /api/orders?page=0&size=10`  
**Then** returns at most 10 results.

**When** `GET /api/orders?page=1&size=10`  
**Then** returns next page (if any).

### UC-API-11 List Orders (Sorting)
**When** `GET /api/orders?sortBy=updatedAt&sortDir=desc`  
**Then** list is sorted by updatedAt descending.

**When** `GET /api/orders?sortBy=createdAt&sortDir=asc`  
**Then** list is sorted by createdAt ascending.

### UC-API-12 List Orders (Date Filters)
**When** `GET /api/orders?updatedFrom=2026-02-01T00:00:00Z`  
**Then** only orders updated after or on that instant.

**When** `GET /api/orders?updatedTo=2026-02-10T23:59:59Z`  
**Then** only orders updated before or on that instant.

## Use Cases (Frontend)

### UC-UI-01 Register Order
**Given** orderId + customerId  
**When** click "Register"  
**Then** Order Snapshot shows created order  
**And** action banner shows "Created order"  
**And** action log includes the action.

### UC-UI-02 Track Order
**When** enter orderId and click "Track"  
**Then** Order Snapshot reflects the order  
**And** action banner shows "Tracked order".

### UC-UI-03 Update Status
**When** enter orderId + status + note and click "Update"  
**Then** Order Snapshot updates  
**And** action banner shows "Updated order".

### UC-UI-04 Action Filters
**When** filter by Order ID  
**Then** action log list is filtered accordingly.

**When** filter by Action Type  
**Then** action log list shows only matching actions.

### UC-UI-05 Action Export
**When** click "Export CSV"  
**Then** a file `order-actions.csv` is downloaded.

### UC-UI-06 All Orders Filter/Export
**When** fill filters and click "Fetch Orders"  
**Then** list displays matching orders.

**When** click "Export CSV"  
**Then** file `orders.csv` is downloaded.

**When** click "Export JSON"  
**Then** file `orders.json` is downloaded.

### UC-UI-07 UI Robustness
**When** IDs or notes are long  
**Then** layout does not overflow and content wraps correctly.

## Auth & Security

- All API requests require Basic Auth except:
  - `/swagger-ui.html`, `/v3/api-docs/**`, `/webjars/**`
  - `/actuator/health`

## Non-Functional

- CORS allowed origins: `http://localhost:5173`, `http://127.0.0.1:5173`
- API should respond within reasonable time for basic list/filter calls.

