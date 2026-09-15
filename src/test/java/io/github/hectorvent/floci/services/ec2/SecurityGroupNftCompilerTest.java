package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpRange;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityGroupNftCompilerTest {

    @Test
    void managedPeersAreCheckedBeforeDockerAddressCidrs() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("tcp");
        permission.setFromPort(443);
        permission.setToPort(443);
        IpRange cidr = new IpRange();
        cidr.setCidrIp("10.0.0.0/16");
        permission.getIpRanges().add(cidr);
        IpRange broadTransport = new IpRange();
        broadTransport.setCidrIp("172.17.0.0/16");
        permission.getIpRanges().add(broadTransport);
        group.getIpPermissions().add(permission);

        var target = endpoint("eni-a", "10.0.0.2", "172.17.0.2", group);
        var allowed = endpoint("eni-b", "10.0.0.3", "172.17.0.3", group);
        var denied = endpoint("eni-c", "10.1.0.3", "172.17.0.4", group);
        String nft = SecurityGroupNftCompiler.compile(target, List.of(allowed, denied), Map.of());

        assertTrue(nft.contains("ip saddr 172.17.0.3 tcp dport 443 accept"));
        assertTrue(nft.indexOf("ip saddr 172.17.0.4 drop")
                < nft.indexOf("ip saddr 172.17.0.0/16 tcp dport 443 accept"));
    }

    private static SecurityGroupNftCompiler.Endpoint endpoint(String eni, String logical,
                                                               String transport, SecurityGroup group) {
        return new SecurityGroupNftCompiler.Endpoint("000000000000", "us-east-1", "vpc-1",
                eni, logical, transport, Set.of("sg-1"), List.of(group));
    }
}
