# Technical Documentation

## Stack

- React 18
- Vite 6
- TypeScript 5
- Plain CSS (no runtime styling deps)

## API Integration

The frontend talks to the Spring Boot API via REST calls:

- POST `/api/orders`
- GET `/api/orders/{id}`
- PUT `/api/orders/{id}/status`

### Authentication

HTTP Basic is used. Configure credentials via Vite env vars:

- `VITE_API_USER`
- `VITE_API_PASSWORD`

### Error Handling

The REST client throws on non-2xx responses. Each panel handles its own error state.

## Configuration

Use `frontend/.env` for local development:

```
VITE_API_BASE_URL=http://localhost:8080
VITE_API_USER=api-user
VITE_API_PASSWORD=change-me
```

## Build

```
npm run build
npm run preview
```

## Deployment Notes

- Set `VITE_API_BASE_URL` to the public backend URL.
- The build output lives in `frontend/dist`.
- The UI is static and can be served via Nginx, S3, or any static host.
