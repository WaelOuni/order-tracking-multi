# Order Tracking Frontend

A React + Vite operator console for the Order Tracking Spring Boot API. The UI is deliberately minimal and workflow-driven: register an order, track it, and update its status safely.

## Quick Start

1. Install dependencies:
   ```bash
   cd frontend
   npm install
   ```

2. Configure environment variables (copy into `frontend/.env`):
   ```bash
   VITE_API_BASE_URL=http://localhost:8080
   VITE_API_USER=api-user
   VITE_API_PASSWORD=change-me
   ```

3. Start the UI:
   ```bash
   npm run dev
   ```

The UI runs on `http://localhost:5173` and calls the backend on port 8080.

## Features

- **Register Order**: POST `/api/orders`
- **Track Order**: GET `/api/orders/{id}`
- **Update Status**: PUT `/api/orders/{id}/status`
- **Order Snapshot**: always shows the most recent response

## Domain Rules (summary)

Order status transitions are enforced server-side. Use only the supported status values:

- `CREATED`
- `PACKED`
- `SHIPPED`
- `DELIVERED`
- `CANCELLED`

If you submit an invalid status, the API returns a 400/409 error and the UI displays it.

## Structure

- `src/api/client.ts`: typed REST client with Basic Auth.
- `src/App.tsx`: UX logic and request flows.
- `src/types.ts`: domain types mirrored from the backend.
- `src/styles.css`: visual system and layout.

## Configuration

The frontend reads these variables at build-time:

- `VITE_API_BASE_URL`
- `VITE_API_USER`
- `VITE_API_PASSWORD`

## Technical Notes

- **Auth**: HTTP Basic, configured in the backend SecurityConfig.
- **Errors**: server errors are surfaced in panel-specific banners.
- **Styling**: uses `Space Grotesk` with a neutral, operations-first palette.

## Next Improvements

- Add a timeline visualization for status history.
- Persist the last used order ID per browser session.
- Add status transition hints (e.g. allowed next steps).
