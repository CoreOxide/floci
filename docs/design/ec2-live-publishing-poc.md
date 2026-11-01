# Live EC2 publishing experiment

This is a test-only experiment for [discussion #4065](https://github.com/orgs/floci-io/discussions/4065).
It does not enable application host publishing under security-group enforcement.

## Run on Windows with Docker Desktop and WSL2

Enable Docker Desktop's WSL2 Linux backend and integration with Ubuntu. Inside Ubuntu,
verify Java 25, `unzip`, `docker info`, `wslinfo --networking-mode`, `wsl.exe --version`,
and `powershell.exe` are available. Do not start another Docker daemon inside Ubuntu.
Run from the repository checkout:

```sh
FLOCI_EC2_PUBLISHING_POC=true sh ./mvnw \
  -Dtest=Ec2LivePublishingPocIntegrationTest test
```

Use a checkout on WSL's Linux filesystem for reasonable build times. Reading the
source tree and Maven cache through `/mnt/c` was substantially slower in this run.

The class is disabled unless explicitly opted in. Once opted in, missing prerequisites
are errors, not successful checks. Run only one copy at a time: the test uses the
`ec2-publishing-poc` Docker resource namespace. It allocates ports from 30000-30999.
Windows PowerShell probes Windows localhost, Java probes Ubuntu localhost, and managed
clients probe both the helper's bridge address and `host.docker.internal`.

Evidence is written to `target/ec2-live-publishing-poc.txt`, including on assertion
failure. A failed topology is an expected possible result of this experiment, but
JUnit still reports failure. Consult the recorded assertions instead of treating
successful helper creation as proof that the topology works.

## Topology and bounds

```text
Windows / Ubuntu / managed client
  -> Docker host port
  -> per-port publishing helper: DNAT, no SNAT, no TCP relay
  -> existing protected namespace: Floci security-group policy
  -> unprivileged TCP echo workload
```

Only helpers have networking privileges. Packet source sets capture IPv4 sources
before filtering and NAT inside the helper and before filtering at the target.
Group creation, authorization and revocation use the AWS SDK against the test server.
The existing firewall manager registers the test's logical ENI identities and applies
the production policy compiler. The workload is a namespace-sharing echo container,
not an SDK-launched EC2 instance; this experiment isolates the transport question.

The target has no outbound application allowance, so successful replies also check
stateful return traffic. Narrow ingress rules ensure the revocation test concerns
tracked connections. AWS's broad-rule untracked-flow exceptions are outside scope.

Explicit `/32` return routes force replies through a publishing helper. This is a
deliberate experimental ceiling: one route per source cannot select different
gateways for two concurrent publications to that source. The second-publication
check measures that constraint without moving the route and silently breaking the
first established flow. Production needs a validated different topology or
connection-aware routing before integration.

Source identity is a separate gate. Managed traffic through a host-published port
must retain its managed transport address so existing logical-CIDR and SG-reference
matching remains meaningful. The test records failure if identity is translated,
even if a broad external rule would make the connection work. It also explicitly
tests whether granting the Docker-visible host source accidentally admits the denied
managed peer. External Windows/Ubuntu source addresses may be replaced by Desktop;
no original external-client-address fidelity is claimed.

Docker Desktop mounts `/proc/sys` read-only even with `NET_ADMIN`. The experiment
uses Docker's existing namespace IPv4 forwarding and an nftables default-drop forward
chain as its activation gate, without adding privileges to change sysctls. DNAT and
the narrow forwarding allowances are installed together in one nftables transaction,
after the target's security-group policy exists.

The test installs rules before permitting forwarded traffic, injects a failed installation,
checks fresh and established flows during revocation and live helper addition, and
checks workload identity, start time and namespace stability. Cleanup removes the
test containers and namespaces, releases ports, and deletes its SDK-created groups
and VPC. Docker-owned firewall rules are never edited.

## Results

Tested on 2026-11-01 from upstream `main` commit `a707cb326`, Floci 2.4.0,
Quarkus 3.40.1. The topology fails acceptance and must not enable production publishing.

| Component | Tested configuration |
|-----------|----------------------|
| Windows | Build 10.0.26200.9711 |
| Docker Desktop CLI | 4.95.0 |
| Docker Engine | 29.8.2, Linux containers |
| WSL | 2.7.14.0, Ubuntu 26.04 LTS, NAT networking |
| Kernel | 6.18.33.2-microsoft-standard-WSL2 |
| Java | OpenJDK 25.0.3-ea+7-Ubuntu-2 |

| Check | Observed result |
|-------|-----------------|
| Direct managed traffic | Logical CIDR and SG-reference rules permit A and deny B; sender egress independently denies a fresh connection. |
| Direct publishing-helper address | A remains `172.17.0.3`, B remains `172.17.0.4`; DNAT preserves identity and SG enforcement works. |
| Managed traffic through `host.docker.internal:30000` | Both peers arrive at helper and target as `172.17.0.1`, losing managed identity before the helper. Docker host resolves to IPv4 `192.168.65.254`. |
| Windows and Ubuntu localhost | Both arrive as `172.17.0.1`; a narrow visible-source CIDR permits echo traffic. |
| Forbidden-peer bypass | Granting that host-source CIDR also admits managed B through the published port. This fails the security gate. |
| Revocation | A fresh Ubuntu connection is denied; the already tracked echo connection continues. |
| Second publication | Adding its helper preserves the first connection, but the second port cannot return traffic with the existing per-source route. |
| Failed policy installation | nftables rejects the injected rule; the helper stays default-deny and exposes no echo path. |
| Workload and cleanup | Container ID, start time and protected namespace remain unchanged; no added workload capabilities; all seven experiment containers are removed. |

The final opted-in JUnit run completes with one failed test, zero errors and zero
skips. Its four failed assertions are identity preservation for A, identity
preservation for B, denial of B after granting host access, and the second port's
return path. These are measured topology failures, not reasons to weaken the assertions.
Full Checkstyle and the final focused Checkstyle check pass.
Without the opt-in variable, a separate run succeeds with one skipped test and no
failures or errors. No experiment containers remain after either run.

The test runner was Ubuntu WSL2, using a temporary native-filesystem source copy and
a native copy of the existing Maven cache. The final invocation was:

```sh
FLOCI_EC2_PUBLISHING_POC=true sh ./mvnw -ntp \
  -Dmaven.repo.local=/home/alexy/.cache/floci-poc-m2 \
  -Dmaven.compiler.useIncrementalCompilation=false \
  -Dtest=Ec2LivePublishingPocIntegrationTest test
```

Raw evidence and the complete final runner log are copied back to the original
checkout as `target/ec2-live-publishing-poc.txt` and
`target/ec2-live-publishing-poc-run.log`. An initial harness issue selecting an IPv6
address was corrected to use `getent ahostsv4`; the results above are from the
subsequent IPv4 run.

The next design must either preserve managed identities before crossing Desktop's
host forwarding path or enforce managed-peer policy before identity is lost. It also
needs a validated return-path solution for concurrent publications. Simply replacing
socat with this DNAT helper is insufficient. Keep the current publishing restriction.

Validation covers this Windows/WSL2/Desktop configuration only; native Linux, IPv6,
UDP, recovery and production connection draining remain deferred.
