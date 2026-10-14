# Deploying the choreography engine

One host runs everything with Docker Compose:

- Kafka and Redis;
- the discovery platform, from the image its own pipeline publishes;
- the engine serving the flow editor and API on port 80;
- two engines hosting the demo services (`assess`, `review`, `reserve`, `notify` and `pay`, `ship`,
  `confirm`, `charge`). They register with discovery like any service.

The flows in `flows/` are loaded when the engine starts.

## Images

Both pipelines push to the GitHub Container Registry after `mvn verify` passes:

| Image | Built by | Tags |
|---|---|---|
| `ghcr.io/ermi9/eda-choreography` | this repo, push to `main` | commit SHA, `latest` |
| `ghcr.io/ermi9/discovery-service` | `microservice-discovery-k8s`, any push | commit SHA, branch name, `latest` on `main` |

Pin both by commit SHA in `.env` (see `.env.example`), so the running system is exactly the one
that was verified.

## The host (AWS EC2)

- Ubuntu 24.04, **t3.medium** (2 vCPU, 4 GB). The whole stack uses about 1.4 GB. A free-tier
  micro instance (1 GB) cannot hold Kafka and four JVMs.
- 30 GB gp3 disk.
- Security group: 22 from your IP, 80 from anywhere.

Install Docker once:

```sh
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2
sudo usermod -aG docker ubuntu   # then log out and in again
```

## Deploy or update

```sh
scp -i key.pem -r deploy ubuntu@<ip>:~/choreography
ssh -i key.pem ubuntu@<ip>
cd ~/choreography
cp .env.example .env    # then put the two commit SHAs in
# If the packages are private: echo <token with read:packages> | docker login ghcr.io -u ermi9 --password-stdin
docker compose pull && docker compose up -d
```

Within about a minute the eight demo services are live. Check that:

```sh
curl -s localhost/api/catalog          # eight services, "live": true
curl -s -X POST -H 'Content-Type: application/json' -d '{"amount": 5000}' localhost/api/flows/order/runs
curl -s localhost/api/runs/<correlationId>   # COMPLETED, with the trace
```

Then open `http://<ip>/` for the editor.

## Things to try in the editor

- **Run `order` with `{"amount": 5000}`:** assess, review, reserve, then pay and ship in
  parallel, joined at confirm.
- **Run `order` with `{"amount": 20}`:** the choice skips review.
- **Run `order` with `{"amount": 20, "failAt": "ship"}`:** ship fails, so pay, reserve and assess
  are undone and the run ends COMPENSATED.
- **Run `sale`:** one `notify` service runs two steps.
- **Draw a task on a service discovery does not know yet:** saving reports it as late-bound. A
  run waits up to 30 s for it, then is undone.

## Costs and idling

A t3.medium is billed per hour while it runs. Stop the instance when no one is using it, and
start it again before a demo. `docker compose` restarts the stack on boot (`restart:
unless-stopped`). After a stop and start the public IP changes, unless an Elastic IP is
attached.

## Logs

```sh
docker compose logs -f services-a services-b   # steps, joins and "demo: … undoes …"
docker compose logs -f discovery               # registrations and health checks
```
