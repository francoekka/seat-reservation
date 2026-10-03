## Production Deployment

### Build & Containerize
- Run `./mvnw clean package -DskipTests`
- Build Docker image: `docker build -t seat-reservation:latest .`

### Run with Docker Compose
- `docker compose up -d`
- App available at `http://localhost:8080`
- Postgres available at `localhost:5432`

### Environment Variables
- `SPRING_DATASOURCE_URL` → JDBC URL for Postgres
- `SPRING_DATASOURCE_USERNAME` → DB user
- `SPRING_DATASOURCE_PASSWORD` → DB password
- `PORT` → service port (default 8080)

### Health & Metrics
- Liveness: `/actuator/health/liveness`
- Readiness: `/actuator/health/readiness`
- Metrics: `/actuator/prometheus`

### Logging
- Structured JSON logs with `x-correlation-id`
- Example log:
  ```json
  {
    "level":"INFO",
    "message":"Reservation confirmed",
    "x-correlation-id":"abc123",
    "service":"seat-reservation"
  }

## Monitoring & Alerts

### Prometheus
- Scrape `/actuator/prometheus`
- Key metrics:
    - `reservations_confirmed_total`
    - `reservations_declined_total{reason=...}`
    - `seats_available_gauge`

### Grafana Dashboards
- Reservation success vs decline rate
- Seat availability trends
- DB readiness status

### Alerts
- Trigger alert if readiness endpoint returns 503 > 1 minute
- Alert if `reservations_declined_total{reason="seat_taken"}` spikes abnormally


## Load Testing

Run hot-seat storm:
```bash
./burst.sh <showId> A1
