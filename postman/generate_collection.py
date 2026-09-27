"""
Generates the ClaimFlow Postman collection (v2.1) and local environment.

    python postman/generate_collection.py

Edit this file, not the JSON: request names, chained variables and test scripts stay consistent.
"""
import json
import os
import re
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))

# ---------------------------------------------------------------------------------------------
# helpers


def M(amount):
    """Money literal emitted exactly as written: json.dumps would turn 500000.00 into 500000.0."""
    return "@@" + amount + "@@"


def exact_decimals(text):
    return re.sub(r'"@@(-?[0-9.]+)@@"', lambda m: m.group(1), text)


def js(*lines):
    return list(lines)


def status_test(code):
    return js(f"pm.test('status is {code}', () => pm.response.to.have.status({code}));")


def save(var, expr):
    return js(f"pm.collectionVariables.set('{var}', {expr});")


def poll_until(target, while_statuses, var_claim, when_done=()):
    """Re-runs this request until the claim leaves the transient statuses (Collection Runner / Newman)."""
    transient = json.dumps(while_statuses)
    return js(
        "const body = pm.response.json();",
        "const n = Number(pm.collectionVariables.get('pollCount') || 0);",
        f"if ({transient}.includes(body.status) && n < 40) {{",
        "  pm.collectionVariables.set('pollCount', n + 1);",
        "  setTimeout(() => {}, 500);                       // wait, then run this request again",
        "  postman.setNextRequest(pm.info.requestName);",
        "} else {",
        "  pm.collectionVariables.set('pollCount', 0);",
        f"  pm.test('claim reached {target}', () => pm.expect(body.status).to.eql('{target}'));",
        f"  pm.collectionVariables.set('{var_claim}_etag', pm.response.headers.get('ETag'));",
        *["  " + line for line in when_done],   # extra assertions run only on the final response
        "}",
    )


def request(name, method, url, body=None, headers=None, tests=None, pre=None, desc=None):
    item = {
        "name": name,
        "request": {
            "method": method,
            "header": [{"key": k, "value": v} for k, v in (headers or {}).items()],
            "url": url,
        },
    }
    if desc:
        item["request"]["description"] = desc
    if body is not None:
        item["request"]["header"].append({"key": "Content-Type", "value": "application/json"})
        item["request"]["body"] = {"mode": "raw", "raw": exact_decimals(json.dumps(body, indent=2)),
                                   "options": {"raw": {"language": "json"}}}
    events = []
    if pre:
        events.append({"listen": "prerequest", "script": {"type": "text/javascript", "exec": pre}})
    if tests:
        events.append({"listen": "test", "script": {"type": "text/javascript", "exec": tests}})
    if events:
        item["event"] = events
    return item


def folder(name, desc, items):
    return {"name": name, "description": desc, "item": items}


API = "{{gatewayUrl}}/api/v1"
UNIQUE = js("pm.collectionVariables.set('unique', Date.now());",
            "pm.collectionVariables.set('today', new Date().toISOString().slice(0, 10));")


def customer_body(prefix):
    return {"firstName": "Priya", "lastName": "Sharma", "email": prefix + ".{{unique}}@example.com",
            "phone": "+919876543210", "dateOfBirth": "1992-08-15"}


def motor_policy_body(customer_var):
    return {"customerId": "{{" + customer_var + "}}", "productType": "MOTOR",
            "startDate": "2026-01-01", "endDate": "2026-12-31", "premium": M("12000.00"),
            "coverages": [{"coverageType": "COLLISION", "limitAmount": M("500000.00"), "deductible": M("20000.00")},
                          {"coverageType": "THEFT", "limitAmount": M("300000.00"), "deductible": M("10000.00")}]}


def fnol_body(policy_var, amount, loss="COLLISION"):
    return {"policyId": "{{" + policy_var + "}}", "lossType": loss, "incidentDate": "{{today}}",
            "description": "Rear-ended at a traffic signal", "claimedAmount": amount}


# ---------------------------------------------------------------------------------------------
# 0. health

health = folder("0. Health", "Gateway health. Works in both run modes.", [
    request("Gateway health", "GET", "{{gatewayUrl}}/actuator/health",
            tests=status_test(200) + js("pm.test('UP', () => pm.expect(pm.response.json().status).to.eql('UP'));")),
])

direct = folder("0b. Direct service health (IDE mode only)",
                "Only when services run with java -jar (README option B). In Docker only the gateway is published.",
                [request(f"{name} health (direct)", "GET", f"{{{{{var}}}}}/actuator/health", tests=status_test(200))
                 for name, var in [("Policy Service", "policyUrl"), ("Claim Service", "claimUrl"),
                                   ("Validation Service", "validationUrl"), ("Payment Service", "paymentUrl")]])

# ---------------------------------------------------------------------------------------------
# 1. policy service

policy = folder("1. Policy Service", "Customers, policies, coverages (PolicyCenter-like). Run top to bottom.", [
    request("Create customer", "POST", f"{API}/customers", body=customer_body("priya"), pre=UNIQUE,
            tests=status_test(201) + save("customerId", "pm.response.json().id")
            + js("pm.test('Location header', () => pm.response.to.have.header('Location'));")),
    request("Get customer", "GET", f"{API}/customers/{{{{customerId}}}}", tests=status_test(200)),
    request("Create MOTOR policy (collision + theft)", "POST", f"{API}/policies", body=motor_policy_body("customerId"),
            tests=status_test(201) + save("policyId", "pm.response.json().id")
            + js("pm.test('policy number format', () => pm.expect(pm.response.json().policyNumber).to.match(/^POL-\\d{4}-\\d{6}$/));",
                 "pm.test('limit keeps its scale', () => pm.expect(pm.response.text()).to.include('\"limitAmount\":500000.00'));")),
    request("Get policy", "GET", f"{API}/policies/{{{{policyId}}}}",
            tests=status_test(200) + js("pm.test('2 coverages', () => pm.expect(pm.response.json().coverages).to.have.length(2));")),
    request("List customer's policies", "GET", f"{API}/customers/{{{{customerId}}}}/policies", tests=status_test(200)),
    request("Coverage check: covered", "GET",
            f"{API}/policies/{{{{policyId}}}}/coverage-check?coverageType=COLLISION&incidentDate=2026-03-10",
            tests=status_test(200) + js("pm.test('covered', () => pm.expect(pm.response.json().reason).to.eql('COVERED'));")),
    request("Coverage check: outside policy period", "GET",
            f"{API}/policies/{{{{policyId}}}}/coverage-check?coverageType=COLLISION&incidentDate=2027-02-01",
            tests=status_test(200) + js("pm.test('outside period', () => pm.expect(pm.response.json().reason).to.eql('OUTSIDE_POLICY_PERIOD'));")),
    request("Create HOME policy (to cancel)", "POST", f"{API}/policies",
            body={"customerId": "{{customerId}}", "productType": "HOME", "startDate": "2026-01-01",
                  "endDate": "2026-12-31", "premium": M("8000.00"),
                  "coverages": [{"coverageType": "FIRE", "limitAmount": M("1000000.00"), "deductible": M("25000.00")}]},
            tests=status_test(201) + save("homePolicyId", "pm.response.json().id")),
    request("Cancel policy", "POST", f"{API}/policies/{{{{homePolicyId}}}}/cancel",
            tests=status_test(200) + js("pm.test('CANCELLED', () => pm.expect(pm.response.json().status).to.eql('CANCELLED'));")),
])

# ---------------------------------------------------------------------------------------------
# 2. adjusters

adjusters = folder("2. Adjusters", "Claims adjusters (reference data).", [
    request("Create adjuster", "POST", f"{API}/adjusters", pre=UNIQUE,
            body={"name": "Ravi Kumar", "email": "ravi.{{unique}}@example.com"},
            tests=status_test(201) + save("adjusterId", "pm.response.json().id")),
    request("List active adjusters", "GET", f"{API}/adjusters", tests=status_test(200)),
])

# ---------------------------------------------------------------------------------------------
# 3. claims

claims = folder("3. Claim Service",
                "FNOL, lifecycle, adjuster assignment, audit trail (ClaimCenter-like). Needs folders 1 and 2 first.", [
    request("File claim (FNOL)", "POST", f"{API}/claims", body=fnol_body("policyId", M("200000.00")),
            headers={"Idempotency-Key": "{{fnolKey}}", "X-User-Id": "agent-7"},
            pre=UNIQUE + js("pm.collectionVariables.set('fnolKey', pm.variables.replaceIn('{{$guid}}'));"),
            tests=status_test(201) + save("claimId", "pm.response.json().id")
            + js("pm.test('starts SUBMITTED', () => pm.expect(pm.response.json().status).to.eql('SUBMITTED'));")),
    request("File claim again, same Idempotency-Key (replay)", "POST", f"{API}/claims", body=fnol_body("policyId", M("200000.00")),
            headers={"Idempotency-Key": "{{fnolKey}}", "X-User-Id": "agent-7"},
            desc="A client retry after a timeout: 200 with the ORIGINAL claim, nothing new created.",
            tests=status_test(200) + js("pm.test('same claim returned', () => pm.expect(pm.response.json().id).to.eql(pm.collectionVariables.get('claimId')));")),
    request("Get claim (wait until validated)", "GET", f"{API}/claims/{{{{claimId}}}}",
            desc="Validation is asynchronous (Kafka). In the Runner this request repeats until the claim leaves SUBMITTED. Saves the ETag.",
            tests=poll_until("UNDER_REVIEW", ["SUBMITTED"], "claim")),
    request("List claims of the policy (by status, paged)", "GET",
            f"{API}/claims?policyId={{{{policyId}}}}&status=UNDER_REVIEW&page=0&size=20",
            tests=status_test(200) + js("pm.test('page shape', () => pm.expect(pm.response.json()).to.have.keys('content','page','size','totalElements','totalPages'));")),
    request("Assign adjuster (If-Match)", "POST", f"{API}/claims/{{{{claimId}}}}/assign-adjuster",
            body={"adjusterId": "{{adjusterId}}"}, headers={"If-Match": "{{claim_etag}}", "X-User-Id": "mgr-1"},
            tests=status_test(200) + save("claim_etag", "pm.response.headers.get('ETag')")),
    request("Approve claim (If-Match)", "PATCH", f"{API}/claims/{{{{claimId}}}}/status",
            body={"targetStatus": "APPROVED", "approvedAmount": M("200000.00")},
            headers={"If-Match": "{{claim_etag}}", "X-User-Id": "adj-ravi"},
            desc="Approval hands the claim to Payment: the response already shows SETTLEMENT_PENDING.",
            tests=status_test(200) + js("pm.test('handed to payment', () => pm.expect(pm.response.json().status).to.eql('SETTLEMENT_PENDING'));")),
    request("Get claim (wait until settled)", "GET", f"{API}/claims/{{{{claimId}}}}",
            tests=poll_until("SETTLED", ["APPROVED", "SETTLEMENT_PENDING", "PAYMENT_INITIATED"], "claim")),
    request("Claim history (audit trail)", "GET", f"{API}/claims/{{{{claimId}}}}/history", tests=status_test(200)),
    request("Close claim", "PATCH", f"{API}/claims/{{{{claimId}}}}/status", body={"targetStatus": "CLOSED"},
            headers={"X-User-Id": "adj-ravi"},
            tests=status_test(200) + js("pm.test('CLOSED, nothing allowed next', () => { const b = pm.response.json(); pm.expect(b.status).to.eql('CLOSED'); pm.expect(b.allowedNextStatuses).to.be.empty; });")),
])

# ---------------------------------------------------------------------------------------------
# 4. payments

payments = folder("4. Payment Service", "Read-only: payments are created by the ClaimApproved event. Needs folder 3 first.", [
    request("Get payment of a claim", "GET", f"{API}/payments?claimId={{{{claimId}}}}",
            tests=status_test(200) + save("paymentId", "pm.response.json().id")
            + js("pm.test('paid 1,80,000.00 (2,00,000 - 20,000 deductible)', () => pm.expect(pm.response.text()).to.include('\"amount\":180000.00'));",
                 "pm.test('COMPLETED', () => pm.expect(pm.response.json().status).to.eql('COMPLETED'));")),
    request("Get payment by id", "GET", f"{API}/payments/{{{{paymentId}}}}", tests=status_test(200)),
])

# ---------------------------------------------------------------------------------------------
# 5. journey (self-contained)

J_EVENTS = ["CLAIM_CREATED", "CLAIM_VALIDATED", "ADJUSTER_ASSIGNED", "CLAIM_APPROVED", "SETTLEMENT_REQUESTED",
            "PAYMENT_INITIATED", "PAYMENT_COMPLETED", "CLAIM_CLOSED"]
journey = folder("5. End-to-end journey (Runner)",
                 "Self-contained: FNOL to CLOSED across all five services, with assertions. Run this folder in the "
                 "Collection Runner (or newman --folder). Uses its own j_* variables.", [
    request("J1 Create customer", "POST", f"{API}/customers", body=customer_body("journey"), pre=UNIQUE,
            tests=status_test(201) + save("j_customerId", "pm.response.json().id")),
    request("J2 Create MOTOR policy", "POST", f"{API}/policies", body=motor_policy_body("j_customerId"),
            tests=status_test(201) + save("j_policyId", "pm.response.json().id")),
    request("J3 FNOL: collision, 2,00,000", "POST", f"{API}/claims", body=fnol_body("j_policyId", M("200000.00")),
            headers={"X-Correlation-ID": "journey-{{unique}}", "X-User-Id": "agent-7"},
            tests=status_test(201) + save("j_claimId", "pm.response.json().id")),
    request("J4 Wait for automatic validation", "GET", f"{API}/claims/{{{{j_claimId}}}}",
            tests=poll_until("UNDER_REVIEW", ["SUBMITTED"], "j", when_done=js(
                "pm.test('coverage terms stored', () => pm.expect(pm.response.text()).to.include('\"deductible\":20000.00'));"))),
    request("J5 Create adjuster", "POST", f"{API}/adjusters", body={"name": "Meera Iyer", "email": "meera.{{unique}}@example.com"},
            tests=status_test(201) + save("j_adjusterId", "pm.response.json().id")),
    request("J6 Assign adjuster", "POST", f"{API}/claims/{{{{j_claimId}}}}/assign-adjuster",
            body={"adjusterId": "{{j_adjusterId}}"}, headers={"If-Match": "{{j_etag}}", "X-User-Id": "mgr-1"},
            tests=status_test(200) + save("j_etag", "pm.response.headers.get('ETag')")),
    request("J7 Approve 2,00,000", "PATCH", f"{API}/claims/{{{{j_claimId}}}}/status",
            body={"targetStatus": "APPROVED", "approvedAmount": M("200000.00")},
            headers={"If-Match": "{{j_etag}}", "X-User-Id": "adj-meera"},
            tests=status_test(200) + js("pm.test('SETTLEMENT_PENDING', () => pm.expect(pm.response.json().status).to.eql('SETTLEMENT_PENDING'));")),
    request("J8 Wait for payment", "GET", f"{API}/claims/{{{{j_claimId}}}}",
            tests=poll_until("SETTLED", ["APPROVED", "SETTLEMENT_PENDING", "PAYMENT_INITIATED"], "j")),
    request("J9 Payment and settlement", "GET", f"{API}/payments?claimId={{{{j_claimId}}}}",
            tests=status_test(200) + js(
                "const t = pm.response.text();",
                "pm.test('paid 180000.00 exactly (scale kept)', () => pm.expect(t).to.include('\"amount\":180000.00'));",
                "pm.test('settlement breakdown', () => { pm.expect(t).to.include('\"approvedAmount\":200000.00'); pm.expect(t).to.include('\"payableAmount\":180000.00'); });",
                "pm.test('not capped', () => pm.expect(pm.response.json().settlement.cappedAtLimit).to.be.false);")),
    request("J10 Close claim", "PATCH", f"{API}/claims/{{{{j_claimId}}}}/status", body={"targetStatus": "CLOSED"},
            headers={"X-User-Id": "adj-meera"}, tests=status_test(200)),
    request("J11 Audit trail: 8 events in order", "GET", f"{API}/claims/{{{{j_claimId}}}}/history",
            tests=status_test(200) + js(
                f"const expected = {json.dumps(J_EVENTS)};",
                "pm.test('full lifecycle recorded in order', () => pm.expect(pm.response.json().map(h => h.eventType)).to.eql(expected));",
                "pm.test('FNOL row has the correlation id', () => pm.expect(pm.response.json()[0].correlationId).to.match(/^journey-/));")),
])

# ---------------------------------------------------------------------------------------------
# 6. errors

E = "e"
errors = folder("6. Error handling and edge cases",
                "One request per error status. Self-contained: E0 requests create what they need.", [
    request("E0a Setup: customer", "POST", f"{API}/customers", body=customer_body("errors"), pre=UNIQUE,
            tests=status_test(201) + save("e_customerId", "pm.response.json().id")
            + save("e_email", "pm.response.json().email")),
    request("E0b Setup: MOTOR policy", "POST", f"{API}/policies", body=motor_policy_body("e_customerId"),
            tests=status_test(201) + save("e_policyId", "pm.response.json().id")),
    request("E0c Setup: claim", "POST", f"{API}/claims", body=fnol_body("e_policyId", M("200000.00")),
            headers={"Idempotency-Key": "{{e_key}}"},
            pre=js("pm.collectionVariables.set('e_key', pm.variables.replaceIn('{{$guid}}'));"),
            tests=status_test(201) + save("e_claimId", "pm.response.json().id")),
    request("E0d Setup: wait until validated", "GET", f"{API}/claims/{{{{e_claimId}}}}",
            tests=poll_until("UNDER_REVIEW", ["SUBMITTED"], "e")),
    request("E0e Setup: adjuster", "POST", f"{API}/adjusters", body={"name": "Err Adj", "email": "err.{{unique}}@example.com"},
            tests=status_test(201) + save("e_adjusterId", "pm.response.json().id")),

    request("400: validation errors listed per field", "POST", f"{API}/claims",
            body={"lossType": "COLLISION", "incidentDate": "2099-01-01", "claimedAmount": -5},
            tests=status_test(400) + js(
                "const fields = pm.response.json().violations.map(v => v.field);",
                "pm.test('field violations', () => pm.expect(fields).to.include.members(['policyId', 'incidentDate', 'description', 'claimedAmount']));",
                "pm.test('correlation id in error body', () => pm.expect(pm.response.json().correlationId).to.be.a('string'));")),
    request("400: unknown enum in query parameter", "GET",
            f"{API}/policies/{{{{e_policyId}}}}/coverage-check?coverageType=ALIENS&incidentDate=2026-03-10",
            tests=status_test(400) + js("pm.test('lists allowed values', () => pm.expect(pm.response.json().message).to.include('allowed: [COLLISION'));")),
    request("400: malformed UUID in path", "GET", f"{API}/claims/not-a-uuid", tests=status_test(400)),
    request("400: page size above 100", "GET", f"{API}/claims?policyId={{{{e_policyId}}}}&size=500",
            tests=status_test(400) + js("pm.test('size violation', () => pm.expect(pm.response.json().violations.map(v => v.field)).to.include('size'));")),
    request("404: unknown policy", "GET", f"{API}/policies/{uuid.UUID(int=1)}", tests=status_test(404)),
    request("404: unknown route", "GET", f"{API}/policies/{{{{e_policyId}}}}/nope", tests=status_test(404)),
    request("404: claim without a payment", "GET", f"{API}/payments?claimId={{{{e_claimId}}}}", tests=status_test(404)),
    request("405: wrong HTTP method", "DELETE", f"{API}/claims/{{{{e_claimId}}}}", tests=status_test(405)),
    request("200: FNOL replay with the same Idempotency-Key", "POST", f"{API}/claims", body=fnol_body("e_policyId", M("200000.00")),
            headers={"Idempotency-Key": "{{e_key}}"},
            tests=status_test(200) + js("pm.test('original claim', () => pm.expect(pm.response.json().id).to.eql(pm.collectionVariables.get('e_claimId')));")),
    request("409: duplicate customer email", "POST", f"{API}/customers",
            body={"firstName": "Copy", "lastName": "Cat", "email": "{{e_email}}", "dateOfBirth": "1990-01-01"},
            tests=status_test(409)),
    request("409: invalid state transition (UNDER_REVIEW to CLOSED)", "PATCH", f"{API}/claims/{{{{e_claimId}}}}/status",
            body={"targetStatus": "CLOSED"},
            tests=status_test(409) + js("pm.test('says what is allowed', () => pm.expect(pm.response.json().message).to.include('allowed'));")),
    request("422: approve without an adjuster", "PATCH", f"{API}/claims/{{{{e_claimId}}}}/status",
            body={"targetStatus": "APPROVED", "approvedAmount": M("100000.00")},
            tests=status_test(422) + js("pm.test('needs adjuster', () => pm.expect(pm.response.json().message).to.include('adjuster'));")),
    request("E1 Assign adjuster with the current ETag", "POST", f"{API}/claims/{{{{e_claimId}}}}/assign-adjuster",
            body={"adjusterId": "{{e_adjusterId}}"}, headers={"If-Match": "{{e_etag}}"},
            tests=status_test(200)),
    request("412: same update with the now-stale ETag", "POST", f"{API}/claims/{{{{e_claimId}}}}/assign-adjuster",
            body={"adjusterId": "{{e_adjusterId}}"}, headers={"If-Match": "{{e_etag}}"},
            desc="The ETag saved in E0d is one version behind after E1: someone else changed the claim.",
            tests=status_test(412) + js("pm.test('explains versions', () => pm.expect(pm.response.json().message).to.include('has changed since you loaded it'));")),
    request("422: approved amount not above the deductible", "PATCH", f"{API}/claims/{{{{e_claimId}}}}/status",
            body={"targetStatus": "APPROVED", "approvedAmount": M("20000.00")},
            tests=status_test(422) + js("pm.test('nothing payable', () => pm.expect(pm.response.json().message).to.include('nothing would be payable'));")),
    request("422: approved amount above the claimed amount", "PATCH", f"{API}/claims/{{{{e_claimId}}}}/status",
            body={"targetStatus": "APPROVED", "approvedAmount": M("250000.00")}, tests=status_test(422)),
    request("422: coverage not offered for the product (FLOOD on MOTOR)", "POST", f"{API}/policies",
            body={"customerId": "{{e_customerId}}", "productType": "MOTOR", "startDate": "2026-01-01",
                  "endDate": "2026-12-31", "premium": M("5000.00"),
                  "coverages": [{"coverageType": "FLOOD", "limitAmount": M("100000.00"), "deductible": M("1000.00")}]},
            tests=status_test(422)),
    request("E2 Cancel the policy", "POST", f"{API}/policies/{{{{e_policyId}}}}/cancel", tests=status_test(200)),
    request("409: cancel an already-cancelled policy", "POST", f"{API}/policies/{{{{e_policyId}}}}/cancel",
            tests=status_test(409)),
])

# ---------------------------------------------------------------------------------------------

collection = {
    "info": {
        "_postman_id": str(uuid.uuid5(uuid.NAMESPACE_URL, "claimflow-postman")),
        "name": "ClaimFlow",
        "description": (
            "All ClaimFlow APIs through the gateway (http://localhost:8000).\n\n"
            "Folders 1-4 are for exploring and chain variables (run them in order). Folder 5 is a self-contained "
            "end-to-end journey with assertions; folder 6 shows every error status. Both 5 and 6 run in the "
            "Collection Runner or with Newman.\n\n"
            "Every request gets an X-Correlation-ID (collection pre-request script), so you can grep for it in the "
            "service logs. Generated by postman/generate_collection.py; edit that, not this file."),
        "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
    },
    "event": [
        {"listen": "prerequest", "script": {"type": "text/javascript", "exec": js(
            "// Trace every request: add a correlation id unless the request sets its own",
            "if (!pm.request.headers.has('X-Correlation-ID')) {",
            "  const id = 'pm-' + pm.info.requestName.toLowerCase().replace(/[^a-z0-9]+/g, '-').slice(0, 40) + '-' + Date.now();",
            "  pm.request.headers.upsert({ key: 'X-Correlation-ID', value: id });",
            "}")}},
        {"listen": "test", "script": {"type": "text/javascript", "exec": js(
            "// Routed API calls only: the gateway's own /actuator endpoints don't pass its routing filters",
            "if (pm.request.url.getPath().startsWith('/api/')) {",
            "  pm.test('gateway echoes X-Correlation-ID', () => pm.response.to.have.header('X-Correlation-ID'));",
            "}")}},
    ],
    "variable": [{"key": k, "value": ""} for k in [
        "unique", "today", "customerId", "policyId", "homePolicyId", "adjusterId", "fnolKey", "claimId",
        "claim_etag", "paymentId", "pollCount", "j_customerId", "j_policyId", "j_claimId", "j_adjusterId", "j_etag",
        "e_customerId", "e_email", "e_policyId", "e_key", "e_claimId", "e_adjusterId", "e_etag"]],
    "item": [health, direct, policy, adjusters, claims, payments, journey, errors],
}

environment = {
    "id": str(uuid.uuid5(uuid.NAMESPACE_URL, "claimflow-postman-env-local")),
    "name": "ClaimFlow local",
    "values": [
        {"key": "gatewayUrl", "value": "http://localhost:8000", "enabled": True},
        {"key": "policyUrl", "value": "http://localhost:8081", "enabled": True},
        {"key": "claimUrl", "value": "http://localhost:8082", "enabled": True},
        {"key": "validationUrl", "value": "http://localhost:8083", "enabled": True},
        {"key": "paymentUrl", "value": "http://localhost:8084", "enabled": True},
    ],
    "_postman_variable_scope": "environment",
}

with open(os.path.join(HERE, "ClaimFlow.postman_collection.json"), "w", encoding="utf-8", newline="\n") as f:
    json.dump(collection, f, indent=2)
    f.write("\n")
with open(os.path.join(HERE, "ClaimFlow-local.postman_environment.json"), "w", encoding="utf-8", newline="\n") as f:
    json.dump(environment, f, indent=2)
    f.write("\n")

count = sum(len(fo["item"]) for fo in collection["item"])
print(f"wrote {count} requests in {len(collection['item'])} folders")
