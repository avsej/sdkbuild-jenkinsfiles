# cxx CI — miniplan for build #20 and next steps

_Branch: `cxx-rework-scripted-build-pipeline-for-cbdinocluster-tarball-ci`_
_Last commits: `ec98fc0` (label params + unit tests on build pool), `b8fe2de` (docker_gwbridge experiment + env report), `93522ed` (v0.0.115 + config generator)._

## Where we are

- **Build / tarball / unit tests:** working. gRPC is bundled in the tarball
  (offline build), ccache is warm, stash is pruned.
- **Unit tests:** now default to the stable swarm build pool
  (`UNIT_LABEL` = `rockylinux9`) instead of the AWS `sdkqe-*` pool.
- **Integration:** blocked. The `sdkqe-rockylinux9` QE cloud template was
  removed (builds #18/#19 aborted at scheduling). `rockylinux9` build pool has
  **no docker/cbdinocluster**, so integration can't move there.
- **Open experiment (untested):** put cluster nodes on `docker_gwbridge` (the
  gateway bridge the agent already shares) so the agent reaches them with **zero
  mutation of the agent's own networks** — the hard rule from build #17, where
  `docker network connect <bridge> <self>` severed the agents.

## Before build #20: set the integration pool

Integration/Capella still default to `sdkqe-rockylinux9` (gone). To get an
integration node, add a **String** build parameter in the Jenkins job:

- `COMBINATION_LABEL` = a **live, docker + cbdinocluster-capable** agent label.
- (optional) `UNIT_LABEL` to move unit tests off `rockylinux9`.

If no docker pool exists yet, that's the real blocker to resolve first (infra).

## What to look for in the build #20 log

1. **Unit tests ran on the build pool** — grep `Running on` near the
   `unit tests` stage → expect `build-rockylinux9-*`; then
   `100% tests passed`. This is the signal we moved off AWS successfully.

2. **Did integration get a node?** grep `no nodes with the label` /
   `Running on`. If `COMBINATION_LABEL` wasn't set → still aborts (expected).

3. **If integration got a node, read the new `docker / network environment
   report`** (printed once, before config generation). Key fields:
   - `swarm=… isManager=…` — manager vs worker (overlay ops need manager).
   - agent network attachments (name/ip/gw) — is it on `docker_gwbridge`?
   - per-network `attachable` flags.
   - `docker_gwbridge … containers=…` — after alloc, did the `cbdynnode`
     containers attach there?

4. **gwbridge verdict** — grep `chosen network` (expect
   `docker_gwbridge … trusting --prefer`), then the post-mortem per-node probes:
   - `from agent 8091: HTTP 401` → **reachable, success.**
   - `from agent 8091: HTTP 000` + `from inside 8091: HTTP 401` → server up but
     still unreachable → gwbridge didn't work; go to fallback.
   - `cbdinocluster alloc` error at container create → daemon refused standalone
     attach to `docker_gwbridge`.

## Decision tree after #20

- **Unit tests green on build pool, integration node still missing** → infra:
  obtain/define a docker-capable label; set `COMBINATION_LABEL`. Re-run.
- **Integration node present, gwbridge probes 401** → done; tidy up
  (remove the `--prefer docker_gwbridge` "experiment" framing, keep it).
- **Integration node present, gwbridge 000 / alloc rejected gwbridge** →
  gwbridge is a dead end. Fall back to:
  1. **cbdinocluster load-balancer / host-published ports** — reach nodes via
     the host gateway from the agent (no shared node network). Needs a dig into
     cbdinocluster's LB connstr (`PassiveLoadBalancer`/`ActiveLoadBalancer`,
     `deployment/dockerdeploy/controller.go` haproxy path).
  2. **Make `sdkqe_jenkins` (or the chosen overlay) `attachable`** in the swarm
     stack definition (infra, one line) — then nodes join the agent's network,
     no agent mutation; the generator already scores an attachable overlay 80.

## Hard-won rules (don't relitigate)

- **Never mutate the agent container's own networks** (`docker network connect/
  disconnect <self>`) — it kills the agent (build #17).
- **`attachable` is create-time-only** — can't toggle it on an existing network
  from the pipeline (no `docker network update`).
- The build pool has the toolchain but **no docker** — unit tests only.
- cbdinocluster config is per-job-isolated via `CBDINOCLUSTER_CONFIG`
  (v0.0.115+); the generator copies `~/.cbdinocluster`, repoints the network,
  disables+blanks Capella. Never write the shared `~/.cbdinocluster`.

## Key files

- `cxx/cxx-scripted-build-pipeline.groovy` — labels at top
  (`COMBINATION_LABEL`, `UNIT_LABEL`, `TARBALL_LABEL`), bring-up ~L1090,
  `reportDockerEnvironment()` / `collectDockerDiagnostics()` helpers.
- `cxx/scripts/generate_cbdinocluster_config.py` — network pick + `--prefer`.
- `cxx/scripts/cbdinocluster.json` — pinned v0.0.115.

## Parked

- Capella: gated off (`CAPELLA_READY=false`) — expired credentials on Jenkins.
- cbdinocluster `--config` fix: merged upstream (v0.0.115, PR #184).
