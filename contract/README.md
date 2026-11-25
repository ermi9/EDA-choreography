# Plug in your own service

Any HTTP service, in any language, can take part in a choreography. It needs four endpoints. It
does not use Kafka, and it needs no library of this project.

| Endpoint | What it does |
|---|---|
| `GET /health` | 2xx while the service can take work |
| `GET <your OpenAPI address>` | an OpenAPI document **in JSON** that declares the two operations below |
| `POST /choreography/steps/{step}` | does a step |
| `POST /choreography/undo/{step}` | undoes it |

[`participant-v1.yaml`](participant-v1.yaml) describes the bodies and answers.

## The four rules

1. **The same key is the same work.** A step can be delivered twice. Remember each
   `Idempotency-Key` together with your answer, in the same transaction as the work, and answer
   a repeat from memory.
2. **An undo can be repeated**, and can come for a step you never did. Answer 2xx either way.
3. **Once a key is undone, it stays undone.** A slow attempt can arrive after its own undo. Answer
   409 and do nothing.
4. **Answer 422 only if you committed nothing.** It fails the step at once and nothing is undone
   at your service.

Any other answer, or none within 10 s, means nobody knows whether the work happened. The engine
tries three times in all under the same key, then records the step as *uncertain* and asks you
to undo it.

`{step}` is your service's name if the flow has one task on it, otherwise the task's id.

## Try it on your machine

1. Start the engine: `cd deploy && cp .env.example .env`, put `latest` in place of both commit
   SHAs and a free port in `PORT`, then `docker compose up -d`.
2. Start your service, listening on all interfaces (`0.0.0.0`), say on port 9000.
3. Check it. This does and undoes a step, so use test data:
   ```sh
   python3 contract/check.py http://localhost:9000 --input '{"sku": "pen", "quantity": 1}'
   ```
4. Register it with discovery:
   ```sh
   curl -X POST localhost:8081/register -H 'Content-Type: application/json' -d '{
     "name": "inventory",
     "url": "http://host.docker.internal:9000",
     "openapiUrl": "http://host.docker.internal:9000/openapi.json"}'
   ```
5. Open the editor. Within a few seconds your service is in the palette. Draw a flow and run it.

If it does not run, `docker compose logs engine` says what your OpenAPI document lacks.

Two worked examples, in Python and in JavaScript, are in
[sample-services](https://github.com/ermi9/sample-services).

## What the engine cannot check

- It calls undo in the right order, but only your service knows whether the work is really undone.
- It calls whatever address is registered. Keep discovery's registration on a private network.
