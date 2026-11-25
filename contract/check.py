#!/usr/bin/env python3
"""Checks a service against the participant contract. Does and undoes one step, so use test data."""
import argparse
import json
import re
import sys
import urllib.error
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("url", help="where the service answers, such as http://localhost:9000")
parser.add_argument("--spec", help="its OpenAPI document (default: <url>/openapi.json)")
parser.add_argument("--step", default="check")
parser.add_argument("--input", default="{}", help="the run's input, as a JSON object")
args = parser.parse_args()
base = args.url.rstrip("/")
failures = 0


def call(method, url, body=None, key=None):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode(errors="replace")
    except OSError as error:
        return 0, str(error)


def check(name, good, seen=""):
    global failures
    failures += not good
    print(("ok    " if good else "FAIL  ") + name + ("" if good else f"  (got {seen})"))


def as_object(text):
    try:
        value = json.loads(text)
    except ValueError:
        return None
    return value if isinstance(value, dict) else None


run = {"correlationId": "check-" + uuid.uuid4().hex, "flowName": "check", "step": args.step,
       "input": json.loads(args.input)}
step = {**run, "visit": 1, "results": {}}
do, undo = f"{base}/choreography/steps/{args.step}", f"{base}/choreography/undo/{args.step}"

status, body = call("GET", base + "/health")
check("health answers 2xx", status // 100 == 2, f"{status} {body[:200]}")

status, body = call("GET", args.spec or base + "/openapi.json")
paths = (as_object(body) or {}).get("paths", {})
for operation in ("steps", "undo"):
    declared = any(re.fullmatch(r"/choreography/%s/\{[^/}]+}" % operation, path) and "post" in methods
                   for path, methods in paths.items())
    check(f"the OpenAPI document declares POST /choreography/{operation}/{{step}}", declared, f"{status} {list(paths)}")

key = "check:" + uuid.uuid4().hex
status, body = call("POST", do, step, key)
first = as_object(body)
check("a step answers 2xx with a JSON object", status // 100 == 2 and first is not None, f"{status} {body[:200]}")
again, repeated = call("POST", do, step, key)
check("the same key gets the same answer", again == status and as_object(repeated) == first, f"{again} {repeated[:200]}")

status, body = call("POST", undo, {**run, "result": first}, key)
check("an undo answers 2xx", status // 100 == 2, f"{status} {body[:200]}")
status, body = call("POST", undo, {**run, "result": first}, key)
check("the same undo again answers 2xx", status // 100 == 2, f"{status} {body[:200]}")
status, body = call("POST", do, step, key)
check("a step that arrives after its undo answers 409", status == 409, f"{status} {body[:200]}")

key = "check:" + uuid.uuid4().hex
status, body = call("POST", undo, {**run, "result": None}, key)
check("an undo of a step never done answers 2xx", status // 100 == 2, f"{status} {body[:200]}")
status, body = call("POST", do, step, key)
check("that step, arriving late, answers 409", status == 409, f"{status} {body[:200]}")

print("Whether the work was really undone is for you to check in the service's own data.")
sys.exit(1 if failures else 0)
