# Postman collection

`ClaimFlow.postman_collection.json` covers every public API through the gateway
(`http://localhost:8000`), plus health checks. `ClaimFlow-local.postman_environment.json` holds the base URLs.

## Import

Postman → **Import** → select both JSON files → choose the **ClaimFlow local** environment (top right).

## Folders

| Folder | What it does | How to use |
|---|---|---|
| 0. Health | gateway health | any time |
| 0b. Direct service health | each service on 8081–8084 | only when services run with `java -jar` (in Docker only the gateway is published) |
| 1. Policy Service | customers, policies, coverage checks, cancel | run top to bottom; saves `customerId`, `policyId` |
| 2. Adjusters | create / list | saves `adjusterId` |
| 3. Claim Service | FNOL, idempotent replay, wait for validation, list, assign (If-Match), approve, wait for payment, history, close | after 1 and 2 |
| 4. Payment Service | payment + settlement breakdown | after 3 |
| **5. End-to-end journey** | FNOL → CLOSED across all five services, 11 steps with assertions (paid **180000.00**, 8 audit events in order) | self-contained: **Run folder** in the Collection Runner |
| **6. Error handling** | one request per status: 400 (fields, enum, UUID, page size), 404, 405, 409, 412, 422, idempotent 200 | self-contained: Run folder |

Every request gets an `X-Correlation-ID` (`pm-<request-name>-<timestamp>`) from the collection's pre-request
script, so you can find it in the service logs (`docker compose logs | grep pm-...`).

**Waiting for async steps:** validation and payment happen over Kafka. The "Wait …" requests repeat
themselves (every 0.5 s, up to 40 times) until the claim leaves the transient status. This works in
the Collection Runner and Newman; if you click them by hand, just send again if the status hasn't changed yet.

## Command line (Newman)

```bash
docker compose --profile apps up -d --build --wait
npx newman run postman/ClaimFlow.postman_collection.json -e postman/ClaimFlow-local.postman_environment.json \
  --folder "5. End-to-end journey (Runner)" --folder "6. Error handling and edge cases"
```
Last full run (all folders except 0b, against the Docker stack): **67 requests, 155 assertions, 0 failures**, ~15 s.
CI runs the same thing (`api-tests` job in `.github/workflows/ci.yml`).

## Editing

The JSON is generated. Change `generate_collection.py` and run `python postman/generate_collection.py`.
Money values in request bodies use `M("500000.00")` so they're written exactly (Python's `json`
would otherwise emit `500000.0`).
