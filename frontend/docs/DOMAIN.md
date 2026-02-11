# Domain Documentation

This module reflects the backend's core order-tracking domain. It does not re-implement domain rules; it surfaces them in the UI for safe operator workflows.

## Concepts

- **Order**: An entity identified by `id` and `customerId`.
- **Status**: The current lifecycle state for the order.
- **Tracking Event**: A historical record of status transitions with timestamp and optional note.

## Status Values

Supported values (as defined in the backend):

- `CREATED`
- `PACKED`
- `SHIPPED`
- `DELIVERED`
- `CANCELLED`

## Typical Flow

1. Register order with `CREATED` status.
2. Move to `PACKED` once prepared.
3. Move to `SHIPPED` when handed to carrier.
4. Move to `DELIVERED` when completed.
5. Use `CANCELLED` when an order cannot continue.

## Contract Behavior

- Invalid status values yield 400 errors.
- Invalid transitions yield 409 errors.
- Error payloads are surfaced in the UI.
