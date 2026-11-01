package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.IpRange;
import software.amazon.awssdk.services.ec2.model.UserIdGroupPair;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Experimental transport only. A failed topology must never enable the production publisher. */
@QuarkusTest
@TestProfile(Ec2LivePublishingPocIntegrationTest.PocProfile.class)
@EnabledIfEnvironmentVariable(named = "FLOCI_EC2_PUBLISHING_POC", matches = "true")
class Ec2LivePublishingPocIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final int APP_PORT = 8080;
    private static final Pattern IPV4 = Pattern.compile("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b");
    private static final Path EVIDENCE = Path.of("target", "ec2-live-publishing-poc.txt");

    public static final class PocProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ec2.mock", "false",
                    "floci.network.security-group-enforcement.enabled", "true",
                    "floci.docker.resource-namespace", "ec2-publishing-poc");
        }
    }

    @Inject SecurityGroupFirewallManager firewall;
    @Inject ContainerLifecycleManager lifecycle;
    @Inject ContainerBuilder builder;
    @Inject PortAllocator allocator;
    @Inject DockerClient docker;
    @Inject EmulatorConfig config;
    @Inject Ec2Service service;

    private final List<String> containers = new ArrayList<>();
    private final List<String> enis = new ArrayList<>();
    private final List<Integer> ports = new ArrayList<>();
    private final List<String> groupIds = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private final StringBuilder evidence = new StringBuilder();
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private String vpc;

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void livePublicationPreservesIdentityAndTrackedConnections() throws Exception {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                assertEquals("linux", docker.infoCmd().exec().getOsType());
                note("Engine", docker.versionCmd().exec().getVersion());
                note("Engine OS", docker.infoCmd().exec().getOperatingSystem());
                note("Java", System.getProperty("java.runtime.version"));
                note("Kernel", command("uname", "-a"));
                note("WSL networking", command("wslinfo", "--networking-mode"));
                note("Desktop", command("docker", "desktop", "version"));
                note("WSL version", command("wsl.exe", "--version"));

                vpc = ec2.createVpc(r -> r.cidrBlock("10.77.0.0/16")).vpc().vpcId();
                String targetGroup = group(ec2, "target");
                String aGroup = group(ec2, "a");
                String bGroup = group(ec2, "b");
                for (String id : groupIds) {
                    List<IpPermission> defaults = ec2.describeSecurityGroups(r -> r.groupIds(id))
                            .securityGroups().getFirst().ipPermissionsEgress();
                    if (!defaults.isEmpty()) {
                        ec2.revokeSecurityGroupEgress(r -> r.groupId(id).ipPermissions(defaults));
                    }
                }
                IpPermission outbound = cidr(0, 65535, "0.0.0.0/0");
                ec2.authorizeSecurityGroupEgress(r -> r.groupId(aGroup).ipPermissions(outbound));
                ec2.authorizeSecurityGroupEgress(r -> r.groupId(bGroup).ipPermissions(outbound));

                SecurityGroupFirewallManager.Namespace target = endpoint(targetGroup, "10.77.0.10", "target");
                SecurityGroupFirewallManager.Namespace a = endpoint(aGroup, "10.77.0.11", "a");
                SecurityGroupFirewallManager.Namespace b = endpoint(bGroup, "10.77.0.12", "b");
                String worker = start(builder.newContainer(helperImage())
                        .withName("floci-poc-worker-" + suffix)
                        .withNetworkMode("container:" + target.helperId())
                        .withUser("65534:65534").withCapDrop("ALL")
                        .withLabels(Map.of(ContainerStorageHelper.SECURITY_GROUP_WORKLOAD_LABEL, "true"))
                        .withEntrypoint(List.of("sh", "-ec"))
                        .withCmd(List.of("socat TCP4-LISTEN:8080,reuseaddr,fork EXEC:/bin/cat & "
                                + "exec socat TCP4-LISTEN:8081,reuseaddr,fork EXEC:/bin/cat")));
                waitForEcho(worker);
                InspectContainerResponse before = docker.inspectContainerCmd(worker).exec();
                observe(target.helperId(), "input");

                IpPermission wrong = cidr(APP_PORT, APP_PORT, "192.0.2.55/32");
                ec2.authorizeSecurityGroupIngress(r -> r.groupId(targetGroup).ipPermissions(wrong));
                check("wrong logical CIDR denies direct A", !probe(a, target, null,
                        target.transportAddress(), APP_PORT, "direct-wrong"));
                ec2.revokeSecurityGroupIngress(r -> r.groupId(targetGroup).ipPermissions(wrong));
                IpPermission allowed = cidr(APP_PORT, APP_PORT, "10.77.0.11/32");
                ec2.authorizeSecurityGroupIngress(r -> r.groupId(targetGroup).ipPermissions(allowed));
                check("logical CIDR permits direct A", probe(a, target, null,
                        target.transportAddress(), APP_PORT, "direct-a"));
                check("logical CIDR denies direct B", !probe(b, target, null,
                        target.transportAddress(), APP_PORT, "direct-b"));
                ec2.revokeSecurityGroupEgress(r -> r.groupId(aGroup).ipPermissions(outbound));
                check("sender egress independently denies new direct flow", !probe(a, target, null,
                        target.transportAddress(), APP_PORT, "direct-no-egress"));
                ec2.authorizeSecurityGroupEgress(r -> r.groupId(aGroup).ipPermissions(outbound));
                ec2.revokeSecurityGroupIngress(r -> r.groupId(targetGroup).ipPermissions(allowed));
                IpPermission reference = IpPermission.builder().ipProtocol("tcp").fromPort(APP_PORT).toPort(APP_PORT)
                        .userIdGroupPairs(UserIdGroupPair.builder().groupId(aGroup).build()).build();
                ec2.authorizeSecurityGroupIngress(r -> r.groupId(targetGroup).ipPermissions(reference));
                check("SG reference permits direct A", probe(a, target, null,
                        target.transportAddress(), APP_PORT, "direct-ref-a"));
                check("SG reference denies direct B", !probe(b, target, null,
                        target.transportAddress(), APP_PORT, "direct-ref-b"));

                Publication publication = publish(target, APP_PORT);
                returnRoute(target, a.transportAddress(), publication.address());
                returnRoute(target, b.transportAddress(), publication.address());
                check("forwarder preserves A on its bridge path", probe(a, target, publication,
                        publication.address(), APP_PORT, "helper-a"));
                check("forwarder denies B on its bridge path", !probe(b, target, publication,
                        publication.address(), APP_PORT, "helper-b"));

                // Capture before granting any external CIDR. A host translation must not be
                // mistaken for the logical identity of a managed client.
                String resolvedHost = exec(a.helperId(), "getent ahostsv4 host.docker.internal").output();
                Matcher hostAddress = IPV4.matcher(resolvedHost);
                assertTrue(hostAddress.find(), () -> "Docker host needs an IPv4 address: " + resolvedHost);
                String host = hostAddress.group();
                note("Managed clients dial Docker host", host);
                probe(a, target, publication, host, publication.port(), "published-a-before-external-rule");
                checkIdentity(target, a, "published A");
                probe(b, target, publication, host, publication.port(), "published-b-before-external-rule");
                checkIdentity(target, b, "published B");

                Set<String> external = new LinkedHashSet<>();
                clear(target, publication);
                note("Windows initial echo", windowsEcho(publication.port(), "windows-initial"));
                recordSources(target, publication, "Windows initial");
                external.addAll(sources(target.helperId()));
                clear(target, publication);
                note("WSL initial echo", hostEcho(publication.port(), "wsl-initial"));
                recordSources(target, publication, "WSL initial");
                Set<String> wslSources = sources(target.helperId());
                external.addAll(wslSources);
                check("Windows/WSL probes reach protected ingress", !external.isEmpty());
                for (String source : external) {
                    returnRoute(target, source, publication.address());
                    IpPermission permission = cidr(APP_PORT, APP_PORT, source + "/32");
                    ec2.authorizeSecurityGroupIngress(r -> r.groupId(targetGroup).ipPermissions(permission));
                }
                check("Windows published echo succeeds with visible-source CIDR",
                        windowsEcho(publication.port(), "windows-allowed"));
                check("WSL published echo succeeds with visible-source CIDR",
                        hostEcho(publication.port(), "wsl-allowed"));
                check("host-source permission cannot admit forbidden managed B", !probe(b, target, publication,
                        host, publication.port(), "published-b-with-external-rule"));
                check("managed A can use the published host port", probe(a, target, publication,
                        host, publication.port(), "published-a-with-external-rule"));
                ec2.revokeSecurityGroupEgress(r -> r.groupId(aGroup).ipPermissions(outbound));
                check("sender egress denies new published flow", !probe(a, target, publication,
                        host, publication.port(), "published-a-no-egress"));
                ec2.authorizeSecurityGroupEgress(r -> r.groupId(aGroup).ipPermissions(outbound));

                if (hostEcho(publication.port(), "wsl-ready")) {
                    try (Socket socket = connect(publication.port())) {
                        check("tracked echo before revocation", echo(socket, "tracked-before"));
                        for (String source : external) {
                            IpPermission permission = cidr(APP_PORT, APP_PORT, source + "/32");
                            ec2.revokeSecurityGroupIngress(r -> r.groupId(targetGroup).ipPermissions(permission));
                        }
                        check("new WSL flow denied after revocation", !hostEcho(publication.port(), "revoked"));
                        check("tracked echo survives revocation", echo(socket, "tracked-after-revoke"));
                        for (String source : external) {
                            IpPermission permission = cidr(APP_PORT + 1, APP_PORT + 1, source + "/32");
                            ec2.authorizeSecurityGroupIngress(r -> r.groupId(targetGroup).ipPermissions(permission));
                        }
                        Publication second = publish(target, APP_PORT + 1);
                        check("tracked echo survives second helper creation", echo(socket, "tracked-after-add"));
                        note("Second publication", second);
                        check("second published port has a working return path",
                                hostEcho(second.port(), "second-publication"));
                        check("first tracked flow survives second publication probe", echo(socket, "tracked-last"));
                    }
                } else {
                    check("tracked-flow experiment can establish its initial connection", false);
                }

                Publication failed = preparePublication(APP_PORT);
                Exec invalid = exec(failed.id(), "printf '%s\\n' 'add rule inet missing chain accept' | nft -f -");
                check("injected policy installation fails", invalid.exit() != 0);
                String failedPolicy = exec(failed.id(), "nft list table ip floci_publish").output();
                check("forwarding stays default-deny after failed installation",
                        failedPolicy.contains("policy drop") && !failedPolicy.contains("accept")
                                && !failedPolicy.contains("dnat"));
                check("failed publication exposes no echo", !hostEcho(failed.port(), "failed-policy"));

                InspectContainerResponse after = docker.inspectContainerCmd(worker).exec();
                check("workload identity unchanged", before.getId().equals(after.getId()));
                check("workload start time unchanged", before.getState().getStartedAt().equals(after.getState().getStartedAt()));
                check("protected namespace unchanged", before.getHostConfig().getNetworkMode()
                        .equals(after.getHostConfig().getNetworkMode()));
                check("workload has no added capabilities", after.getHostConfig().getCapAdd() == null
                        || after.getHostConfig().getCapAdd().length == 0);
                note("Target routes", exec(target.helperId(), "ip -4 route").output());
                note("Target nftables", exec(target.helperId(), "nft list ruleset").output());
                note("Publisher nftables", exec(publication.id(), "nft list ruleset").output());
                assertTrue(failures.isEmpty(), () -> "Topology failed: " + failures + ". See " + EVIDENCE);
            } catch (Exception | AssertionError e) {
                note("Experiment failure", e);
                throw e;
            } finally {
                try {
                    cleanup(ec2);
                } finally {
                    Files.createDirectories(EVIDENCE.getParent());
                    Files.writeString(EVIDENCE, evidence + "\nFailed assertions: " + failures + "\n");
                }
            }
        }
    }

    private String group(Ec2Client ec2, String name) {
        String id = ec2.createSecurityGroup(r -> r.vpcId(vpc).groupName("poc-" + name + "-" + suffix)
                .description("Live publishing experiment")).groupId();
        groupIds.add(id);
        return id;
    }

    private SecurityGroupFirewallManager.Namespace endpoint(String group, String logical, String name) {
        SecurityGroupFirewallManager.Namespace namespace = firewall.createNamespace("ec2", "poc-" + name + "-" + suffix,
                ACCOUNT, REGION, Optional.empty(), Map.of());
        containers.add(namespace.helperId());
        String eni = "eni-poc-" + name + "-" + suffix;
        enis.add(eni);
        SecurityGroup model = service.describeSecurityGroups(REGION, List.of(group), List.of(), Map.of()).getFirst();
        firewall.register(new SecurityGroupNftCompiler.Endpoint(ACCOUNT, REGION, vpc, eni, logical,
                namespace.transportAddress(), Set.of(group), List.of(model)), namespace.helperId(), Map.of());
        note(name + " identity", logical + " -> " + namespace.transportAddress() + " group=" + group);
        return namespace;
    }

    private String helperImage() {
        return config.network().securityGroupEnforcement().helperImage();
    }

    private String start(ContainerBuilder.Builder spec) {
        String id = lifecycle.createAndStart(spec.build()).containerId();
        containers.add(id);
        return id;
    }

    private Publication preparePublication(int appPort) throws Exception {
        int port = allocator.allocate(30000, 30999);
        ports.add(port);
        String id = start(builder.newContainer(helperImage()).withName("floci-poc-publish-" + port + "-" + suffix)
                .withCapAdd("NET_ADMIN").withPortBinding(appPort, port)
                .withEntrypoint(List.of("sleep")).withCmd(List.of("2147483647")));
        String address = docker.inspectContainerCmd(id).exec().getNetworkSettings().getNetworks()
                .values().iterator().next().getIpAddress();
        assertEquals("1", exec(id, "cat /proc/sys/net/ipv4/ip_forward").output().strip(),
                "Docker helper requires namespace IPv4 forwarding, enabled by Docker's bridge driver");
        mustExec(id, "nft 'add table ip floci_publish'; "
                + "nft 'add chain ip floci_publish forward { type filter hook forward priority -10; policy drop; }'");
        observe(id, "prerouting");
        return new Publication(id, address, port);
    }

    private Publication publish(SecurityGroupFirewallManager.Namespace target, int appPort) throws Exception {
        Publication publication = preparePublication(appPort);
        String rules = "add chain ip floci_publish nat { type nat hook prerouting priority -100; }\n"
                + "add rule ip floci_publish nat ip daddr " + publication.address() + " tcp dport " + appPort
                + " dnat to " + target.transportAddress() + ":" + appPort + "\n"
                + "add rule ip floci_publish forward ct state established,related counter accept\n"
                + "add rule ip floci_publish forward ip daddr " + target.transportAddress()
                + " tcp dport " + appPort + " counter accept\n";
        mustExec(publication.id(), "printf %s '" + rules + "' | nft -f -");
        note("Publication activated", publication + " -> " + target.transportAddress() + ":" + appPort);
        return publication;
    }

    private void observe(String id, String hook) throws Exception {
        mustExec(id, "nft 'add table inet floci_observe'; "
                + "nft 'add set inet floci_observe sources { type ipv4_addr; flags dynamic; }'; "
                + "nft 'add chain inet floci_observe capture { type filter hook " + hook + " priority -301; }'; "
                + "nft 'add rule inet floci_observe capture tcp dport {8080,8081} "
                + "update @sources { ip saddr } counter'");
    }

    private void returnRoute(SecurityGroupFirewallManager.Namespace target, String source, String gateway) throws Exception {
        // ponytail: one return gateway per controlled source; simultaneous publications for
        // the same source need a different topology or connection-aware policy routing.
        mustExec(target.helperId(), "ip route replace " + source + "/32 via " + gateway);
    }

    private boolean probe(SecurityGroupFirewallManager.Namespace source, SecurityGroupFirewallManager.Namespace target,
                          Publication publication, String destination, int port, String label) throws Exception {
        clear(target, publication);
        Exec result = exec(source.helperId(), "printf '%s\\n' " + label + " | nc -w 2 " + destination + " " + port);
        note(label + " echo", result.output().strip());
        recordSources(target, publication, label);
        return result.output().strip().equals(label);
    }

    private void checkIdentity(SecurityGroupFirewallManager.Namespace target, SecurityGroupFirewallManager.Namespace source,
                               String label) throws Exception {
        check(label + " retains managed transport identity", sources(target.helperId()).equals(Set.of(source.transportAddress())));
    }

    private void clear(SecurityGroupFirewallManager.Namespace target, Publication publication) throws Exception {
        mustExec(target.helperId(), "nft flush set inet floci_observe sources");
        if (publication != null) {
            mustExec(publication.id(), "nft flush set inet floci_observe sources");
        }
    }

    private Set<String> sources(String id) throws Exception {
        Set<String> addresses = new LinkedHashSet<>();
        Matcher matcher = IPV4.matcher(exec(id, "nft list set inet floci_observe sources").output());
        while (matcher.find()) {
            addresses.add(matcher.group());
        }
        return addresses;
    }

    private void recordSources(SecurityGroupFirewallManager.Namespace target, Publication publication, String label) throws Exception {
        if (publication != null) {
            note(label + " helper sources", sources(publication.id()));
        }
        note(label + " target sources", sources(target.helperId()));
    }

    private Socket connect(int port) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            socket.setSoTimeout(2000);
            return socket;
        } catch (Exception e) {
            socket.close();
            throw e;
        }
    }

    private boolean hostEcho(int port, String message) {
        try (Socket socket = connect(port)) {
            return echo(socket, message);
        } catch (Exception e) {
            note(message + " WSL connection", e.toString());
            return false;
        }
    }

    private boolean echo(Socket socket, String message) throws Exception {
        OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
        writer.write(message + "\n");
        writer.flush();
        return message.equals(new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine());
    }

    private boolean windowsEcho(int port, String message) throws Exception {
        String script = "$ErrorActionPreference='Stop'; $c=[Net.Sockets.TcpClient]::new(); try { "
                + "$t=$c.ConnectAsync('127.0.0.1'," + port + "); if(-not $t.Wait(2000)){exit 2}; "
                + "$c.ReceiveTimeout=2000; $s=$c.GetStream(); $w=[IO.StreamWriter]::new($s); "
                + "$w.WriteLine('" + message + "'); $w.Flush(); $r=[IO.StreamReader]::new($s); "
                + "if($r.ReadLine() -eq '" + message + "'){exit 0}; exit 3 "
                + "} catch {exit 4} finally {$c.Dispose()}";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Windows probe timed out");
            note(message + " Windows exit", process.exitValue());
            return process.exitValue() == 0;
        } finally {
            process.destroyForcibly();
        }
    }

    private void waitForEcho(String worker) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (exec(worker, "nc -z -w 1 127.0.0.1 8080").exit() == 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Echo workload did not start");
    }

    private Exec exec(String id, String script) throws Exception {
        String execId = docker.execCreateCmd(id).withAttachStdout(true).withAttachStderr(true)
                .withCmd("sh", "-ec", script).exec().getId();
        StringBuilder output = new StringBuilder();
        try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
                output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
            }
        }) {
            assertTrue(docker.execStartCmd(execId).exec(callback).awaitCompletion(10, TimeUnit.SECONDS),
                    "Docker exec timed out: " + script);
        }
        return new Exec(docker.inspectExecCmd(execId).exec().getExitCodeLong(), output.toString());
    }

    private void mustExec(String id, String script) throws Exception {
        Exec result = exec(id, script);
        assertEquals(0L, result.exit(), () -> script + "\n" + result.output());
    }

    private String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Command timed out: " + List.of(args));
            assertEquals(0, process.exitValue(), "Command failed: " + List.of(args));
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).replace("\u0000", "").strip();
        } finally {
            process.destroyForcibly();
        }
    }

    private static IpPermission cidr(int from, int to, String cidr) {
        return IpPermission.builder().ipProtocol("tcp").fromPort(from).toPort(to)
                .ipRanges(IpRange.builder().cidrIp(cidr).build()).build();
    }

    private void check(String label, boolean passed) {
        note(passed ? "PASS" : "FAIL", label);
        if (!passed) {
            failures.add(label);
        }
    }

    private void note(String label, Object value) {
        evidence.append(label).append(": ").append(value).append('\n');
    }

    private void cleanup(Ec2Client ec2) {
        // Keep cleaning after an individual failure, without hiding the failed cleanup.
        List<String> reverse = new ArrayList<>(containers);
        Collections.reverse(reverse);
        for (String id : reverse) {
            try {
                lifecycle.removeIfExistsStrict(id);
                note("PASS", "container removed " + id);
            } catch (Exception e) {
                note("Cleanup error", e);
                failures.add("container cleanup " + id);
            }
        }
        for (String eni : enis) {
            try {
                firewall.unregister(eni);
            } catch (Exception e) {
                note("Cleanup error", e);
                failures.add("endpoint cleanup " + eni);
            }
        }
        ports.forEach(allocator::release);
        for (String group : groupIds) {
            try {
                ec2.deleteSecurityGroup(r -> r.groupId(group));
            } catch (Exception e) {
                note("Cleanup error", e);
                failures.add("security group cleanup " + group);
            }
        }
        if (vpc != null) {
            try {
                ec2.deleteVpc(r -> r.vpcId(vpc));
            } catch (Exception e) {
                note("Cleanup error", e);
                failures.add("VPC cleanup " + vpc);
            }
        }
        assertFalse(failures.stream().anyMatch(failure -> failure.contains("cleanup")),
                () -> "Cleanup failed: " + failures);
    }

    private record Publication(String id, String address, int port) {}
    private record Exec(long exit, String output) {}
}
