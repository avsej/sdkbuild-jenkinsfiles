#!/usr/bin/env python3
"""Generate a per-job cbdinocluster config pinned to a Docker network the
Jenkins agent can actually reach.

THE PROBLEM (build #13): on the shared sdkqe swarm agents, `cbdinocluster
init --auto` cannot create its own "dinonet" and falls back to a user-defined
bridge (e.g. "fit") that the agent container is NOT attached to. Cluster nodes
then come up healthy but unreachable from the agent (HTTP 000 on 8091), and
cbdinocluster's in-agent readiness poll spins until the bring-up cap fires.

THE FIX: copy an existing cbdinocluster config (default ~/.cbdinocluster) and
rewrite docker.network to a network the agent IS attached to, so the cluster
nodes and the agent share an L3 network with working embedded DNS. Write the
result to a per-job path used via `cbdinocluster --config <path> ...`
(v0.0.115+), so the shared ~/.cbdinocluster of co-tenant jobs is never touched.
The Capella deployer is disabled too: its credentials are unmanaged on these
agents and make `cbdinocluster cleanup` fail with PasswordExpiredError.

This also emits a full docker-network inventory BEFORE it changes anything, so
the build log records the topology the decision was made from.

Pure stdlib (argparse + json + subprocess + re): the integration agents are
not guaranteed to have PyYAML, and the config is cbdinocluster-generated YAML
with a stable, flat structure that targeted line editing handles safely.
"""
import argparse
import json
import re
import shutil
import subprocess
import sys

# Networks that can never host a reachable multi-node cluster co-located with
# the agent: docker internals and special modes. Excluded from selection.
_SKIP_NETWORKS = {"host", "none", "ingress", "docker_gwbridge"}


def _run(cmd):
    """Run a command, returning stdout (str). Raises on non-zero exit."""
    return subprocess.run(
        cmd, check=True, capture_output=True, text=True
    ).stdout


def docker_network_inventory():
    """Return {name: inspect_dict} for every docker network on the host."""
    names = _run(["docker", "network", "ls", "--format", "{{.Name}}"]).split()
    inventory = {}
    for name in names:
        try:
            data = json.loads(_run(["docker", "network", "inspect", name]))
            if data:
                inventory[name] = data[0]
        except (subprocess.CalledProcessError, json.JSONDecodeError, IndexError):
            # A network can disappear between ls and inspect (concurrent jobs);
            # skip it rather than abort the whole analysis.
            continue
    return inventory


def agent_networks(agent, inventory):
    """Names of networks the agent container is attached to.

    Primary source is `docker inspect <agent>`; if that fails (e.g. the
    hostname is not directly resolvable as a container ref), fall back to
    scanning each network's container membership for the agent id/prefix.
    """
    try:
        data = json.loads(_run(["docker", "inspect", agent]))
        if data:
            nets = set(data[0].get("NetworkSettings", {}).get("Networks", {}).keys())
            if nets:
                return nets
    except (subprocess.CalledProcessError, json.JSONDecodeError, IndexError):
        pass

    found = set()
    for name, info in inventory.items():
        for cid, cinfo in (info.get("Containers") or {}).items():
            # agent may be a short id, full id, or hostname-ish; match loosely.
            if cid.startswith(agent) or agent.startswith(cid[:12]) \
                    or cinfo.get("Name", "") == agent:
                found.add(name)
    return found


def _net_attrs(info):
    driver = info.get("Driver", "")
    attachable = bool(info.get("Attachable", False))
    subnets = [c.get("Subnet", "") for c in (info.get("IPAM", {}).get("Config") or [])]
    return driver, attachable, ",".join(s for s in subnets if s)


def score_network(name, info):
    """Rank a candidate network for hosting cluster nodes reachable from the
    agent. Higher is better; 0 means unusable. Returns (score, reason)."""
    if name in _SKIP_NETWORKS:
        return 0, "docker-internal/special network"
    driver, attachable, _ = _net_attrs(info)
    if driver == "bridge" and name != "bridge":
        return 100, "user-defined bridge (embedded DNS, attachable)"
    if driver == "overlay" and attachable:
        return 80, "attachable overlay (embedded DNS)"
    if driver == "overlay" and not attachable:
        return 0, "overlay is NOT attachable; standalone cluster nodes cannot join"
    if name == "bridge":
        return 20, "default bridge: usable but no inter-container DNS"
    return 10, "non-standard driver '%s'" % driver


def print_inventory(inventory, on_agent):
    """Emit the BEFORE-manipulation docker-network diagnostic."""
    print("===== docker network inventory (before config generation) =====")
    print("%-22s %-9s %-11s %-7s %s" % ("NAME", "DRIVER", "ATTACHABLE", "AGENT?", "SUBNET"))
    for name in sorted(inventory):
        driver, attachable, subnet = _net_attrs(inventory[name])
        print("%-22s %-9s %-11s %-7s %s" % (
            name, driver, str(attachable).lower(),
            "yes" if name in on_agent else "-", subnet))
    print("agent attached to: %s" % (", ".join(sorted(on_agent)) or "<none>"))


def choose_network(inventory, on_agent, prefer):
    """Pick the best network the agent shares. Returns (name, score, reason)
    or (None, 0, reason) when nothing usable is shared."""
    if prefer:
        if prefer not in inventory:
            return None, 0, "preferred network '%s' does not exist on this host" % prefer
        if prefer not in on_agent:
            return None, 0, "preferred network '%s' exists but the agent is not attached to it" % prefer
        score, reason = score_network(prefer, inventory[prefer])
        return prefer, score, "explicitly preferred; " + reason

    best = (None, 0, "the agent shares no usable network with the docker host")
    for name in sorted(on_agent):
        if name not in inventory:
            continue
        score, reason = score_network(name, inventory[name])
        if score > best[1]:
            best = (name, score, reason)
    return best


def set_in_section(text, section, key, value):
    """Set `section.key` to `value` in cbdinocluster's flat YAML, preserving
    every other line. Replaces an existing key within the (top-level) section,
    or inserts it right after the section header if absent. Creates neither
    section nor file — those are handled by the caller."""
    lines = text.splitlines()
    out = []
    in_section = False
    section_header_idx = None
    replaced = False
    child_indent = "    "  # cbdinocluster marshals with 4-space indent

    for line in lines:
        header = re.match(r"^(\S[^:]*):\s*$", line)
        if header is not None:
            in_section = header.group(1) == section
            if in_section:
                section_header_idx = len(out)
            out.append(line)
            continue
        if in_section:
            child = re.match(r"^(\s+)([\w-]+):\s*(.*)$", line)
            if child is not None:
                child_indent = child.group(1)
                if child.group(2) == key:
                    out.append("%s%s: %s" % (child_indent, key, value))
                    replaced = True
                    continue
        out.append(line)

    if not replaced:
        if section_header_idx is None:
            raise SystemExit(
                "generate_cbdinocluster_config: section '%s' not found in config" % section)
        out.insert(section_header_idx + 1, "%s%s: %s" % (child_indent, key, value))

    return "\n".join(out) + "\n"


def minimal_config():
    """Fallback base when no source config exists (fresh agent). cbdinocluster
    fills the rest from defaults; we set only what the docker deployer needs."""
    return (
        "version: 6\n"
        "docker:\n"
        '    enabled: "true"\n'
        "    host: unix:///var/run/docker.sock\n"
        "    network: \n"
        "capella:\n"
        '    enabled: "false"\n'
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default=None,
                        help="existing config to copy (default: ~/.cbdinocluster). "
                             "If missing, a minimal config is generated.")
    parser.add_argument("--output", required=True,
                        help="per-job config path to write")
    parser.add_argument("--agent-container", required=True,
                        help="the Jenkins agent container id/hostname")
    parser.add_argument("--prefer", default=None,
                        help="force this network if the agent is attached to it")
    args = parser.parse_args()

    inventory = docker_network_inventory()
    on_agent = agent_networks(args.agent_container, inventory)
    print_inventory(inventory, on_agent)

    name, score, reason = choose_network(inventory, on_agent, args.prefer)
    if name is None or score == 0:
        sys.exit(
            "ERROR: no Docker network usable for a reachable cluster — %s.\n"
            "       The agent must share an attachable user-defined bridge (or "
            "attachable overlay) with the cluster nodes; otherwise nodes come up "
            "unreachable (build #13). Failing fast instead of hanging until the "
            "bring-up timeout." % reason)
    print("chosen network: %s (score %d — %s)" % (name, score, reason))
    if score <= 20:
        print("WARNING: '%s' is a weak choice (%s); cluster bring-up may still "
              "fail. Proceeding so the build log captures the outcome." % (name, reason))

    import os
    source = args.source or os.path.expanduser("~/.cbdinocluster")
    if os.path.isfile(source) and os.path.abspath(source) != os.path.abspath(args.output):
        shutil.copyfile(source, args.output)
        print("copied base config from %s" % source)
        with open(args.output) as f:
            text = f.read()
    elif os.path.isfile(args.output):
        # in-place patch (source == output, or only output exists)
        with open(args.output) as f:
            text = f.read()
        print("patching existing config at %s" % args.output)
    else:
        text = minimal_config()
        print("no source config found; generated a minimal base")

    text = set_in_section(text, "docker", "enabled", '"true"')
    text = set_in_section(text, "docker", "network", name)
    text = set_in_section(text, "capella", "enabled", '"false"')
    # The copied base config carries the agent's Capella credentials. We disable
    # the Capella deployer, so blank the secrets too: keeps them out of the
    # workspace file and the build log, with no functional loss.
    for secret in ("username", "password", "override-token",
                   "Internal-support-token", "organization-id"):
        text = set_in_section(text, "capella", secret, "")

    with open(args.output, "w") as f:
        f.write(text)

    print("===== generated %s =====" % args.output)
    # Echo ONLY the docker section (network/host/enabled) — never the capella or
    # github sections, which carry credentials even after blanking.
    show = False
    for line in text.splitlines():
        header = re.match(r"^(\S[^:]*):\s*$", line)
        if header is not None:
            show = header.group(1) == "docker"
        if show:
            print(line)
    print("capella deployer: disabled (credentials blanked)")


if __name__ == "__main__":
    main()
