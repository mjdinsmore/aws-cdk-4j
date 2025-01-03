package io.dataspray.aws.cdk.context;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import io.dataspray.aws.cdk.CdkException;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import software.amazon.awscdk.cloud_assembly_schema.VpcContextQuery;
import software.amazon.awscdk.cxapi.VpcSubnetGroupType;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonValue;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public class VpcNetworkContextProviderMapperTest {

    @DataProvider
    public Object[][] describeVpcsRequestTestDataProvider() {
        return new Object[][]{
                {
                        VpcContextQuery.builder()
                                .region("someRegion")
                                .account("someAccount")
                                .filter(ImmutableMap.of())
                                .build(),
                        DescribeVpcsRequest.builder()
                                .filters(ImmutableList.of())
                                .build()
                },
                {
                        VpcContextQuery.builder()
                                .region("someRegion")
                                .account("someAccount")
                                .filter(ImmutableMap.of())
                                .filter(ImmutableMap.<String, String>builder()
                                        .put("filter-1", "filter-1-value")
                                        .put("filter-2", "filter-2-value")
                                        .build())
                                .build(),
                        DescribeVpcsRequest.builder()
                                .filters(
                                        Filter.builder().name("filter-1").values("filter-1-value").build(),
                                        Filter.builder().name("filter-2").values("filter-2-value").build()
                                )
                                .build()
                },
        };
    }

    @Test(dataProvider = "describeVpcsRequestTestDataProvider")
    public void testDescribeVpcsRequest(VpcContextQuery properties, DescribeVpcsRequest expectedRequest) {
        Ec2Client ec2Client = mock(Ec2Client.class);
        ArgumentCaptor<DescribeVpcsRequest> requestCaptor = ArgumentCaptor.forClass(DescribeVpcsRequest.class);
        when(ec2Client.describeVpcs(requestCaptor.capture()))
                .thenReturn(DescribeVpcsResponse.builder().build());

        AwsClientProvider clientProvider = mock(AwsClientProvider.class);
        when(clientProvider.getClient(any(), any()))
                .thenReturn(ec2Client);

        try {
            new VpcNetworkContextProviderMapper(clientProvider).getContextValue(properties);
        } catch (CdkException e) {
            // Ignoring the exceptions as we're only interested in the vpc
        }

        DescribeVpcsRequest request = requestCaptor.getValue();
        Assert.assertEquals(request, expectedRequest);

    }

    @DataProvider
    public Object[][] testDataProvider() {
        return new Object[][]{
                // Exactly one VPC matching the criteria is required, otherwise an exception is thrown
                {
                        VpcContextQuery.builder()
                                .region("someRegion")
                                .account("someAccount")
                                .filter(ImmutableMap.of())
                                .build(),
                        ClientMockData.get(),
                        null,
                        CdkException.class
                },
                {
                        VpcContextQuery.builder()
                                .region("someRegion")
                                .account("someAccount")
                                .filter(ImmutableMap.of())
                                .build(),
                        ClientMockData.get()
                                .withVpc(vpc("vpc-1", "1.1.1.1/16"))
                                .withVpc(vpc("vpc-2", "1.2.1.1/16")),
                        null,
                        CdkException.class
                },
                // The VPC has one subnetwork of each type: public, private and isolated
                {
                        VpcContextQuery.builder()
                                .region("someRegion")
                                .account("someAccount")
                                .filter(ImmutableMap.of())
                                .build(),
                        ClientMockData.get()
                                .withVpc(vpc("vpc-1", "1.1.1.1/16"))
                                .withVpnGateway(vpnGateway("vpn-gtw-1", "vpc-1"))
                                .withRouteTable(RouteTable.builder()
                                        .routeTableId("private-route-table-1")
                                        .vpcId("vpc-1")
                                        .associations(routeTableAssociation("private-subnet-1"))
                                        .build())
                                .withSubnet(Subnet.builder()
                                        .subnetId("private-subnet-1")
                                        .vpcId("vpc-1")
                                        .availabilityZone("az1")
                                        .tags(tag("aws-cdk:subnet-type", "Private"))
                                        .build())
                                .withRouteTable(RouteTable.builder()
                                        .routeTableId("public-route-table-1")
                                        .vpcId("vpc-1")
                                        .associations(routeTableAssociation("public-subnet-1"))
                                        .build())
                                .withSubnet(Subnet.builder()
                                        .subnetId("public-subnet-1")
                                        .vpcId("vpc-1")
                                        .availabilityZone("az1")
                                        .tags(tag("aws-cdk:subnet-type", "Public"))
                                        .build())
                                .withRouteTable(RouteTable.builder()
                                        .routeTableId("isolated-route-table-1")
                                        .vpcId("vpc-1")
                                        .associations(routeTableAssociation("isolated-subnet-1"))
                                        .build())
                                .withSubnet(Subnet.builder()
                                .subnetId("isolated-subnet-1")
                                .vpcId("vpc-1")
                                .availabilityZone("az1")
                                .tags(tag("aws-cdk:subnet-type", "Isolated"))
                                .build()),
                        VpcContext.builder()
                                .vpcId("vpc-1")
                                .vpcCidrBlock("1.1.1.1/16")
                                .vpnGatewayId("vpn-gtw-1")
                                .availabilityZones(ImmutableList.of("az1"))
                                .privateSubnetIds(ImmutableList.of("private-subnet-1"))
                                .privateSubnetNames(ImmutableList.of("Private"))
                                .privateSubnetRouteTableIds(ImmutableList.of("private-route-table-1"))
                                .publicSubnetIds(ImmutableList.of("public-subnet-1"))
                                .publicSubnetNames(ImmutableList.of("Public"))
                                .publicSubnetRouteTableIds(ImmutableList.of("public-route-table-1"))
                                .isolatedSubnetIds(ImmutableList.of("isolated-subnet-1"))
                                .isolatedSubnetNames(ImmutableList.of("Isolated"))
                                .isolatedSubnetRouteTableIds(ImmutableList.of("isolated-route-table-1"))
                                .build(),
                        null
                },
                // Multiple subnetworks are associated with the same route table
                {
                        VpcContextQuery.builder()
                                .region("someRegion")
                                .account("someAccount")
                                .filter(ImmutableMap.of())
                                .build(),
                        ClientMockData.get()
                                .withVpc(vpc("vpc-1", "1.1.1.1/16"))
                                .withVpnGateway(vpnGateway("vpn-gtw-1", "vpc-1"))
                                .withRouteTable(RouteTable.builder()
                                        .routeTableId("public-route-table-1")
                                        .vpcId("vpc-1")
                                        .associations(
                                                routeTableAssociation("public-subnet-1"),
                                                routeTableAssociation("public-subnet-2")
                                        )
                                        .build())
                                .withSubnet(Subnet.builder()
                                        .subnetId("public-subnet-1")
                                        .vpcId("vpc-1")
                                        .availabilityZone("az1")
                                        .tags(tag("aws-cdk:subnet-type", "Public"))
                                        .build())
                                .withSubnet(Subnet.builder()
                                .subnetId("public-subnet-2")
                                .vpcId("vpc-1")
                                .availabilityZone("az2")
                                .tags(tag("aws-cdk:subnet-type", "Public"))
                                .build()),
                        VpcContext.builder()
                                .vpcId("vpc-1")
                                .vpcCidrBlock("1.1.1.1/16")
                                .vpnGatewayId("vpn-gtw-1")
                                .availabilityZones(ImmutableList.of("az1", "az2"))
                                .publicSubnetIds(ImmutableList.of("public-subnet-1", "public-subnet-2"))
                                .publicSubnetNames(ImmutableList.of("Public"))
                                .publicSubnetRouteTableIds(ImmutableList.of("public-route-table-1", "public-route-table-1"))
                                .build(),
                        null
                },
                // Subnetworks associated with the same route table have different group names
                {
                        VpcContextQuery.builder()
                                .region("someRegion")
                                .account("someAccount")
                                .filter(ImmutableMap.of())
                                .build(),
                        ClientMockData.get()
                                .withVpc(vpc("vpc-1", "1.1.1.1/16"))
                                .withVpnGateway(vpnGateway("vpn-gtw-1", "vpc-1"))
                                .withRouteTable(RouteTable.builder()
                                        .routeTableId("public-route-table-1")
                                        .vpcId("vpc-1")
                                        .associations(
                                                routeTableAssociation("public-subnet-1"),
                                                routeTableAssociation("public-subnet-2")
                                        )
                                        .build())
                                .withSubnet(Subnet.builder()
                                        .subnetId("public-subnet-1")
                                        .vpcId("vpc-1")
                                        .availabilityZone("az1")
                                        .tags(tag("aws-cdk:subnet-type", "Public"), tag("aws-cdk:subnet-name", "name-1"))
                                        .build())
                                .withSubnet(Subnet.builder()
                                .subnetId("public-subnet-2")
                                .vpcId("vpc-1")
                                .availabilityZone("az1")
                                .tags(tag("aws-cdk:subnet-type", "Public"), tag("aws-cdk:subnet-name", "name-2"))
                                .build()),
                        VpcContext.builder()
                                .vpcId("vpc-1")
                                .vpcCidrBlock("1.1.1.1/16")
                                .vpnGatewayId("vpn-gtw-1")
                                .availabilityZones(ImmutableList.of("az1"))
                                .publicSubnetIds(ImmutableList.of("public-subnet-1", "public-subnet-2"))
                                .publicSubnetNames(ImmutableList.of("name-1", "name-2"))
                                .publicSubnetRouteTableIds(ImmutableList.of("public-route-table-1", "public-route-table-1"))
                                .build(),
                        null
                },
                // Subnetworks associated with the same route table have different group names: subnetGroupNameTag is defined
                {
                        VpcContextQuery.builder()
                                .region("someRegion")
                                .account("someAccount")
                                .subnetGroupNameTag("name")
                                .filter(ImmutableMap.<String, String>builder()
                                        .put("filter-1", "filter-1-value")
                                        .put("filter-2", "filter-2-value")
                                        .build())
                                .build(),
                        ClientMockData.get()
                                .withVpc(vpc("vpc-1", "1.1.1.1/16"))
                                .withVpnGateway(vpnGateway("vpn-gtw-1", "vpc-1"))
                                .withRouteTable(RouteTable.builder()
                                        .routeTableId("public-route-table-1")
                                        .vpcId("vpc-1")
                                        .associations(
                                                routeTableAssociation("public-subnet-1"),
                                                routeTableAssociation("public-subnet-2")
                                        )
                                        .build())
                                .withSubnet(Subnet.builder()
                                        .subnetId("public-subnet-1")
                                        .vpcId("vpc-1")
                                        .availabilityZone("az1")
                                        .tags(
                                                tag("aws-cdk:subnet-type", "Public"),
                                                tag("name", "name-1"),
                                                tag("aws-cdk:subnet-name", "different-name-1")
                                        )
                                        .build())
                                .withSubnet(Subnet.builder()
                                .subnetId("public-subnet-2")
                                .vpcId("vpc-1")
                                .availabilityZone("az1")
                                .tags(
                                        tag("aws-cdk:subnet-type", "Public"),
                                        tag("name", "name-2"),
                                        tag("aws-cdk:subnet-name", "different-name-2")
                                )
                                .build()),
                        VpcContext.builder()
                                .vpcId("vpc-1")
                                .vpcCidrBlock("1.1.1.1/16")
                                .vpnGatewayId("vpn-gtw-1")
                                .availabilityZones(ImmutableList.of("az1"))
                                .publicSubnetIds(ImmutableList.of("public-subnet-1", "public-subnet-2"))
                                .publicSubnetNames(ImmutableList.of("name-1", "name-2"))
                                .publicSubnetRouteTableIds(ImmutableList.of("public-route-table-1", "public-route-table-1"))
                                .build(),
                        null
                },
                // Main route table is associated in case there's no route table associated with a subnetwork
                {
                        VpcContextQuery.builder()
                                .region("someRegion")
                                .account("someAccount")
                                .filter(ImmutableMap.of())
                                .build(),
                        ClientMockData.get()
                                .withVpc(vpc("vpc-1", "1.1.1.1/16"))
                                .withVpnGateway(vpnGateway("vpn-gtw-1", "vpc-1"))
                                .withRouteTable(RouteTable.builder()
                                        .routeTableId("main-route-table")
                                        .vpcId("vpc-1")
                                        .associations(mainRouteTableAssociation())
                                        .build())
                                .withSubnet(Subnet.builder()
                                .subnetId("public-subnet-1")
                                .vpcId("vpc-1")
                                .availabilityZone("az1")
                                .tags(tag("aws-cdk:subnet-type", "Public"))
                                .build()),
                        VpcContext.builder()
                                .vpcId("vpc-1")
                                .vpcCidrBlock("1.1.1.1/16")
                                .vpnGatewayId("vpn-gtw-1")
                                .availabilityZones(ImmutableList.of("az1"))
                                .publicSubnetIds(ImmutableList.of("public-subnet-1"))
                                .publicSubnetNames(ImmutableList.of("Public"))
                                .publicSubnetRouteTableIds(ImmutableList.of("main-route-table"))
                                .build(),
                        null
                },
                // An exception is thrown if there's no route table associated with a subnetwork (there's no main route table)
                {
                        VpcContextQuery.builder()
                                .region("someRegion")
                                .account("someAccount")
                                .filter(ImmutableMap.of())
                                .build(),
                        ClientMockData.get()
                                .withVpc(vpc("vpc-1", "1.1.1.1/16"))
                                .withVpnGateway(vpnGateway("vpn-gtw-1", "vpc-1"))
                                .withSubnet(Subnet.builder()
                                .subnetId("public-subnet-1")
                                .vpcId("vpc-1")
                                .availabilityZone("az1")
                                .tags(tag("aws-cdk:subnet-type", "Public"))
                                .build()),
                        null,
                        CdkException.class
                },
                // Public subnetwork is determined using by 'mapPublicIpOnLaunch' flag: mapPublicIpOnLaunch is true
                {
                        VpcContextQuery.builder()
                                .region("someRegion")
                                .account("someAccount")
                                .filter(ImmutableMap.of())
                                .build(),
                        ClientMockData.get()
                                .withVpc(vpc("vpc-1", "1.1.1.1/16"))
                                .withVpnGateway(vpnGateway("vpn-gtw-1", "vpc-1"))
                                .withRouteTable(RouteTable.builder()
                                        .routeTableId("public-route-table-1")
                                        .vpcId("vpc-1")
                                        .associations(routeTableAssociation("public-subnet-1"))
                                        .build())
                                .withSubnet(Subnet.builder()
                                .subnetId("public-subnet-1")
                                .vpcId("vpc-1")
                                .availabilityZone("az1")
                                .mapPublicIpOnLaunch(true)
                                .build()),
                        VpcContext.builder()
                                .vpcId("vpc-1")
                                .vpcCidrBlock("1.1.1.1/16")
                                .vpnGatewayId("vpn-gtw-1")
                                .availabilityZones(ImmutableList.of("az1"))
                                .publicSubnetIds(ImmutableList.of("public-subnet-1"))
                                .publicSubnetNames(ImmutableList.of("Public"))
                                .publicSubnetRouteTableIds(ImmutableList.of("public-route-table-1"))
                                .build(),
                        null
                },
                // Public subnetwork is determined using by 'mapPublicIpOnLaunch' flag: mapPublicIpOnLaunch is false
                {
                        VpcContextQuery.builder()
                                .region("someRegion")
                                .account("someAccount")
                                .filter(ImmutableMap.of())
                                .build(),
                        ClientMockData.get()
                                .withVpc(vpc("vpc-1", "1.1.1.1/16"))
                                .withVpnGateway(vpnGateway("vpn-gtw-1", "vpc-1"))
                                .withRouteTable(RouteTable.builder()
                                        .routeTableId("private-route-table-1")
                                        .vpcId("vpc-1")
                                        .associations(routeTableAssociation("private-subnet-1"))
                                        .build())
                                .withSubnet(Subnet.builder()
                                .subnetId("private-subnet-1")
                                .vpcId("vpc-1")
                                .availabilityZone("az1")
                                .mapPublicIpOnLaunch(false)
                                .build()),
                        VpcContext.builder()
                                .vpcId("vpc-1")
                                .vpcCidrBlock("1.1.1.1/16")
                                .vpnGatewayId("vpn-gtw-1")
                                .availabilityZones(ImmutableList.of("az1"))
                                .privateSubnetIds(ImmutableList.of("private-subnet-1"))
                                .privateSubnetNames(ImmutableList.of("Private"))
                                .privateSubnetRouteTableIds(ImmutableList.of("private-route-table-1"))
                                .build(),
                        null
                },
                // Public subnetwork is determined using route with Internet Gateway: there's an Internet Gateway
                {
                        VpcContextQuery.builder()
                                .region("someRegion")
                                .account("someAccount")
                                .filter(ImmutableMap.of())
                                .build(),
                        ClientMockData.get()
                                .withVpc(vpc("vpc-1", "1.1.1.1/16"))
                                .withVpnGateway(vpnGateway("vpn-gtw-1", "vpc-1"))
                                .withSubnet(subnet("public-subnet-1", "vpc-1", "az1", false))
                                .withRouteTable(RouteTable.builder()
                                .routeTableId("public-route-table-1")
                                .vpcId("vpc-1")
                                .associations(routeTableAssociation("public-subnet-1"))
                                .routes(Route.builder().gatewayId("igw-1").build())
                                .build()),
                        VpcContext.builder()
                                .vpcId("vpc-1")
                                .vpcCidrBlock("1.1.1.1/16")
                                .vpnGatewayId("vpn-gtw-1")
                                .availabilityZones(ImmutableList.of("az1"))
                                .publicSubnetIds(ImmutableList.of("public-subnet-1"))
                                .publicSubnetNames(ImmutableList.of("Public"))
                                .publicSubnetRouteTableIds(ImmutableList.of("public-route-table-1"))
                                .build(),
                        null
                },
                // Public subnetwork is determined using route with Internet Gateway: there's an Internet Gateway
                {
                        VpcContextQuery.builder()
                                .region("someRegion")
                                .account("someAccount")
                                .filter(ImmutableMap.of())
                                .build(),
                        ClientMockData.get()
                                .withVpc(vpc("vpc-1", "1.1.1.1/16"))
                                .withVpnGateway(vpnGateway("vpn-gtw-1", "vpc-1"))
                                .withRouteTable(RouteTable.builder()
                                        .routeTableId("public-route-table-1")
                                        .vpcId("vpc-1")
                                        .associations(routeTableAssociation("public-subnet-1"))
                                        .routes(
                                                route("10.0.0.0/16", "local"),
                                                route("0.0.0.0/0", "igw-1")
                                        )
                                        .build())
                                .withSubnet(Subnet.builder()
                                .subnetId("public-subnet-1")
                                .vpcId("vpc-1")
                                .availabilityZone("az1")
                                .build()),
                        VpcContext.builder()
                                .vpcId("vpc-1")
                                .vpcCidrBlock("1.1.1.1/16")
                                .vpnGatewayId("vpn-gtw-1")
                                .availabilityZones(ImmutableList.of("az1"))
                                .publicSubnetIds(ImmutableList.of("public-subnet-1"))
                                .publicSubnetNames(ImmutableList.of("Public"))
                                .publicSubnetRouteTableIds(ImmutableList.of("public-route-table-1"))
                                .build(),
                        null
                },
                // Public subnetwork is determined using route with Internet Gateway: there's no Internet Gateway
                {
                        VpcContextQuery.builder()
                                .region("someRegion")
                                .account("someAccount")
                                .filter(ImmutableMap.of())
                                .build(),
                        ClientMockData.get()
                                .withVpc(vpc("vpc-1", "1.1.1.1/16"))
                                .withVpnGateway(vpnGateway("vpn-gtw-1", "vpc-1"))
                                .withRouteTable(RouteTable.builder()
                                        .routeTableId("private-route-table-1")
                                        .vpcId("vpc-1")
                                        .associations(routeTableAssociation("private-subnet-1"))
                                        .routes(
                                                route("10.0.0.0/16", "local"),
                                                route("0.0.0.0/0", "nat-1")
                                        )
                                        .build())
                                .withSubnet(Subnet.builder()
                                .subnetId("private-subnet-1")
                                .vpcId("vpc-1")
                                .availabilityZone("az1")
                                .build()),
                        VpcContext.builder()
                                .vpcId("vpc-1")
                                .vpcCidrBlock("1.1.1.1/16")
                                .vpnGatewayId("vpn-gtw-1")
                                .availabilityZones(ImmutableList.of("az1"))
                                .privateSubnetIds(ImmutableList.of("private-subnet-1"))
                                .privateSubnetNames(ImmutableList.of("Private"))
                                .privateSubnetRouteTableIds(ImmutableList.of("private-route-table-1"))
                                .build(),
                        null
                },
                // An exception is expected if the grouped subnetworks have different availability zones
                {
                        VpcContextQuery.builder()
                                .region("someRegion")
                                .account("someAccount")
                                .filter(ImmutableMap.of())
                                .build(),
                        ClientMockData.get()
                                .withVpc(vpc("vpc-1", "1.1.1.1/16"))
                                .withVpnGateway(vpnGateway("vpn-gtw-1", "vpc-1"))
                                .withRouteTable(RouteTable.builder()
                                        .routeTableId("public-route-table-1")
                                        .vpcId("vpc-1")
                                        .associations(
                                                routeTableAssociation("public-subnet-1"),
                                                routeTableAssociation("public-subnet-2")
                                        )
                                        .build())
                                .withSubnet(Subnet.builder()
                                        .subnetId("public-subnet-1")
                                        .vpcId("vpc-1")
                                        .availabilityZone("az1")
                                        .tags(tag("aws-cdk:subnet-type", "Public"))
                                        .build())
                                .withSubnet(Subnet.builder()
                                        .subnetId("public-subnet-2")
                                        .vpcId("vpc-1")
                                        .availabilityZone("az2")
                                        .tags(tag("aws-cdk:subnet-type", "Public"))
                                        .build())
                                .withRouteTable(RouteTable.builder()
                                        .routeTableId("private-route-table-1")
                                        .vpcId("vpc-1")
                                        .associations(
                                                routeTableAssociation("private-subnet-1"),
                                                routeTableAssociation("private-subnet-2")
                                        )
                                        .build())
                                .withSubnet(Subnet.builder()
                                        .subnetId("private-subnet-1")
                                        .vpcId("vpc-1")
                                        .availabilityZone("az2")
                                        .tags(tag("aws-cdk:subnet-type", "Private"))
                                        .build())
                                .withSubnet(Subnet.builder()
                                .subnetId("private-subnet-2")
                                .vpcId("vpc-1")
                                .availabilityZone("az3")
                                .tags(tag("aws-cdk:subnet-type", "Private"))
                                .build()),
                        null,
                        CdkException.class
                },
                // Asymmetric VPCs: the VPC has one subnetwork of each type: public, private and isolated
                {
                        VpcContextQuery.builder()
                                .region("someRegion")
                                .account("someAccount")
                                .filter(ImmutableMap.of())
                                .returnAsymmetricSubnets(true)
                                .build(),
                        ClientMockData.get()
                                .withVpc(vpc("vpc-1", "1.1.1.1/16"))
                                .withVpnGateway(vpnGateway("vpn-gtw-1", "vpc-1"))
                                .withRouteTable(RouteTable.builder()
                                        .routeTableId("private-route-table-1")
                                        .vpcId("vpc-1")
                                        .associations(routeTableAssociation("private-subnet-1"))
                                        .build())
                                .withSubnet(Subnet.builder()
                                        .subnetId("private-subnet-1")
                                        .vpcId("vpc-1")
                                        .availabilityZone("az1")
                                        .cidrBlock("1.1.1.1/24")
                                        .tags(tag("aws-cdk:subnet-type", "Private"))
                                        .build())
                                .withRouteTable(RouteTable.builder()
                                        .routeTableId("public-route-table-1")
                                        .vpcId("vpc-1")
                                        .associations(routeTableAssociation("public-subnet-1"))
                                        .build())
                                .withSubnet(Subnet.builder()
                                        .subnetId("public-subnet-1")
                                        .vpcId("vpc-1")
                                        .availabilityZone("az1")
                                        .cidrBlock("1.1.2.1/24")
                                        .tags(tag("aws-cdk:subnet-type", "Public"))
                                        .build())
                                .withRouteTable(RouteTable.builder()
                                        .routeTableId("isolated-route-table-1")
                                        .vpcId("vpc-1")
                                        .associations(routeTableAssociation("isolated-subnet-1"))
                                        .build())
                                .withSubnet(Subnet.builder()
                                .subnetId("isolated-subnet-1")
                                .vpcId("vpc-1")
                                .availabilityZone("az1")
                                .cidrBlock("1.1.3.1/24")
                                .tags(tag("aws-cdk:subnet-type", "Isolated"))
                                .build()),
                        VpcContext.builder()
                                .vpcId("vpc-1")
                                .vpcCidrBlock("1.1.1.1/16")
                                .vpnGatewayId("vpn-gtw-1")
                                .availabilityZones(ImmutableList.of())
                                .subnetGroups(ImmutableList.of(
                                        VpcContextSubnetGroup.builder()
                                                .name("Private")
                                                .type(VpcSubnetGroupType.PRIVATE)
                                                .subnets(ImmutableList.of(
                                                        VpcContextSubnet.builder()
                                                                .subnetId("private-subnet-1")
                                                                .availabilityZone("az1")
                                                                .cidr("1.1.1.1/24")
                                                                .routeTableId("private-route-table-1")
                                                                .build()))
                                                .build(),
                                        VpcContextSubnetGroup.builder()
                                                .name("Public")
                                                .type(VpcSubnetGroupType.PUBLIC)
                                                .subnets(ImmutableList.of(
                                                        VpcContextSubnet.builder()
                                                                .subnetId("public-subnet-1")
                                                                .availabilityZone("az1")
                                                                .cidr("1.1.2.1/24")
                                                                .routeTableId("public-route-table-1")
                                                                .build()))
                                                .build(),
                                        VpcContextSubnetGroup.builder()
                                                .name("Isolated")
                                                .type(VpcSubnetGroupType.ISOLATED)
                                                .subnets(ImmutableList.of(
                                                        VpcContextSubnet.builder()
                                                                .subnetId("isolated-subnet-1")
                                                                .availabilityZone("az1")
                                                                .cidr("1.1.3.1/24")
                                                                .routeTableId("isolated-route-table-1")
                                                                .build()))
                                                .build()))
                                .build(),
                        null
                },
                // Asymmetric VPCs: 1 private and 3 public subnetworks
                {
                        VpcContextQuery.builder()
                                .region("someRegion")
                                .account("someAccount")
                                .filter(ImmutableMap.of())
                                .returnAsymmetricSubnets(true)
                                .build(),
                        ClientMockData.get()
                                .withVpc(vpc("vpc-1", "1.1.1.1/16"))
                                .withVpnGateway(vpnGateway("vpn-gtw-1", "vpc-1"))
                                .withRouteTable(RouteTable.builder()
                                        .routeTableId("main")
                                        .vpcId("vpc-1")
                                        .associations(mainRouteTableAssociation())
                                        .build())
                                .withSubnet(Subnet.builder()
                                        .subnetId("private-subnet-1")
                                        .vpcId("vpc-1")
                                        .availabilityZone("az1")
                                        .cidrBlock("1.1.1.1/24")
                                        .tags(tag("aws-cdk:subnet-type", "Private"))
                                        .build())
                                .withSubnet(Subnet.builder()
                                        .subnetId("public-subnet-1")
                                        .vpcId("vpc-1")
                                        .availabilityZone("az1")
                                        .cidrBlock("1.1.2.1/24")
                                        .tags(tag("aws-cdk:subnet-type", "Public"))
                                        .build())
                                .withSubnet(Subnet.builder()
                                        .subnetId("public-subnet-2")
                                        .vpcId("vpc-1")
                                        .availabilityZone("az2")
                                        .cidrBlock("1.1.3.1/24")
                                        .tags(tag("aws-cdk:subnet-type", "Public"))
                                        .build())
                                .withSubnet(Subnet.builder()
                                .subnetId("public-subnet-3")
                                .vpcId("vpc-1")
                                .availabilityZone("az3")
                                .cidrBlock("1.1.4.1/24")
                                .tags(tag("aws-cdk:subnet-type", "Public"))
                                .build()),
                        VpcContext.builder()
                                .vpcId("vpc-1")
                                .vpcCidrBlock("1.1.1.1/16")
                                .vpnGatewayId("vpn-gtw-1")
                                .availabilityZones(ImmutableList.of())
                                .subnetGroups(ImmutableList.of(
                                        VpcContextSubnetGroup.builder()
                                                .name("Private")
                                                .type(VpcSubnetGroupType.PRIVATE)
                                                .subnets(ImmutableList.of(
                                                        VpcContextSubnet.builder()
                                                                .subnetId("private-subnet-1")
                                                                .availabilityZone("az1")
                                                                .cidr("1.1.1.1/24")
                                                                .routeTableId("main")
                                                                .build()))
                                                .build(),
                                        VpcContextSubnetGroup.builder()
                                                .name("Public")
                                                .type(VpcSubnetGroupType.PUBLIC)
                                                .subnets(ImmutableList.of(
                                                        VpcContextSubnet.builder()
                                                                .subnetId("public-subnet-1")
                                                                .availabilityZone("az1")
                                                                .cidr("1.1.2.1/24")
                                                                .routeTableId("main")
                                                                .build(),
                                                        VpcContextSubnet.builder()
                                                                .subnetId("public-subnet-2")
                                                                .availabilityZone("az2")
                                                                .cidr("1.1.3.1/24")
                                                                .routeTableId("main")
                                                                .build(),
                                                        VpcContextSubnet.builder()
                                                                .subnetId("public-subnet-3")
                                                                .availabilityZone("az3")
                                                                .cidr("1.1.4.1/24")
                                                                .routeTableId("main")
                                                                .build()))
                                                .build()))
                                .build(),
                        null
                },
                // Asymmetric VPCs: specifying group names using 'aws-cdk:subnet-name' tag'
                {
                        VpcContextQuery.builder()
                                .region("someRegion")
                                .account("someAccount")
                                .filter(ImmutableMap.of())
                                .returnAsymmetricSubnets(true)
                                .build(),
                        ClientMockData.get()
                                .withVpc(vpc("vpc-1", "1.1.1.1/16"))
                                .withVpnGateway(vpnGateway("vpn-gtw-1", "vpc-1"))
                                .withRouteTable(RouteTable.builder()
                                        .routeTableId("main")
                                        .vpcId("vpc-1")
                                        .associations(mainRouteTableAssociation())
                                        .build())
                                .withSubnet(Subnet.builder()
                                        .subnetId("private-subnet-1")
                                        .vpcId("vpc-1")
                                        .availabilityZone("az1")
                                        .cidrBlock("1.1.1.1/24")
                                        .tags(tag("aws-cdk:subnet-type", "Private"), tag("aws-cdk:subnet-name", "internal"))
                                        .build())
                                .withSubnet(Subnet.builder()
                                        .subnetId("public-subnet-1")
                                        .vpcId("vpc-1")
                                        .availabilityZone("az1")
                                        .cidrBlock("1.1.2.1/24")
                                        .tags(tag("aws-cdk:subnet-type", "Public"), tag("aws-cdk:subnet-name", "public-1"))
                                        .build())
                                .withSubnet(Subnet.builder()
                                .subnetId("public-subnet-2")
                                .vpcId("vpc-1")
                                .availabilityZone("az2")
                                .cidrBlock("1.1.3.1/24")
                                .tags(tag("aws-cdk:subnet-type", "Public"), tag("aws-cdk:subnet-name", "public-2"))
                                .build()),
                        VpcContext.builder()
                                .vpcId("vpc-1")
                                .vpcCidrBlock("1.1.1.1/16")
                                .vpnGatewayId("vpn-gtw-1")
                                .availabilityZones(ImmutableList.of())
                                .subnetGroups(ImmutableList.of(
                                        VpcContextSubnetGroup.builder()
                                                .name("internal")
                                                .type(VpcSubnetGroupType.PRIVATE)
                                                .subnets(ImmutableList.of(
                                                        VpcContextSubnet.builder()
                                                                .subnetId("private-subnet-1")
                                                                .availabilityZone("az1")
                                                                .cidr("1.1.1.1/24")
                                                                .routeTableId("main")
                                                                .build()))
                                                .build(),
                                        VpcContextSubnetGroup.builder()
                                                .name("public-1")
                                                .type(VpcSubnetGroupType.PUBLIC)
                                                .subnets(ImmutableList.of(
                                                        VpcContextSubnet.builder()
                                                                .subnetId("public-subnet-1")
                                                                .availabilityZone("az1")
                                                                .cidr("1.1.2.1/24")
                                                                .routeTableId("main")
                                                                .build()))
                                                .build(),
                                        VpcContextSubnetGroup.builder()
                                                .name("public-2")
                                                .type(VpcSubnetGroupType.PUBLIC)
                                                .subnets(ImmutableList.of(
                                                        VpcContextSubnet.builder()
                                                                .subnetId("public-subnet-2")
                                                                .availabilityZone("az2")
                                                                .cidr("1.1.3.1/24")
                                                                .routeTableId("main")
                                                                .build()))
                                                .build()))
                                .build(),
                        null
                },
                // Asymmetric VPCs: specifying group names customized group name tag 'group-name'
                {
                        VpcContextQuery.builder()
                                .region("someRegion")
                                .account("someAccount")
                                .filter(ImmutableMap.of())
                                .returnAsymmetricSubnets(true)
                                .subnetGroupNameTag("group-name")
                                .build(),
                        ClientMockData.get()
                                .withVpc(vpc("vpc-1", "1.1.1.1/16"))
                                .withVpnGateway(vpnGateway("vpn-gtw-1", "vpc-1"))
                                .withRouteTable(RouteTable.builder()
                                        .routeTableId("main")
                                        .vpcId("vpc-1")
                                        .associations(mainRouteTableAssociation())
                                        .build())
                                .withSubnet(Subnet.builder()
                                        .subnetId("private-subnet-1")
                                        .vpcId("vpc-1")
                                        .availabilityZone("az1")
                                        .cidrBlock("1.1.1.1/24")
                                        .tags(tag("aws-cdk:subnet-type", "Private"), tag("group-name", "internal"))
                                        .build())
                                .withSubnet(Subnet.builder()
                                        .subnetId("public-subnet-1")
                                        .vpcId("vpc-1")
                                        .availabilityZone("az1")
                                        .cidrBlock("1.1.2.1/24")
                                        .tags(tag("aws-cdk:subnet-type", "Public"), tag("group-name", "public-1"))
                                        .build())
                                .withSubnet(Subnet.builder()
                                .subnetId("public-subnet-2")
                                .vpcId("vpc-1")
                                .availabilityZone("az2")
                                .cidrBlock("1.1.3.1/24")
                                .tags(tag("aws-cdk:subnet-type", "Public"), tag("group-name", "public-2"))
                                .build()),
                        VpcContext.builder()
                                .vpcId("vpc-1")
                                .vpcCidrBlock("1.1.1.1/16")
                                .vpnGatewayId("vpn-gtw-1")
                                .availabilityZones(ImmutableList.of())
                                .subnetGroups(ImmutableList.of(
                                        VpcContextSubnetGroup.builder()
                                                .name("internal")
                                                .type(VpcSubnetGroupType.PRIVATE)
                                                .subnets(ImmutableList.of(
                                                        VpcContextSubnet.builder()
                                                                .subnetId("private-subnet-1")
                                                                .availabilityZone("az1")
                                                                .cidr("1.1.1.1/24")
                                                                .routeTableId("main")
                                                                .build()))
                                                .build(),
                                        VpcContextSubnetGroup.builder()
                                                .name("public-1")
                                                .type(VpcSubnetGroupType.PUBLIC)
                                                .subnets(ImmutableList.of(
                                                        VpcContextSubnet.builder()
                                                                .subnetId("public-subnet-1")
                                                                .availabilityZone("az1")
                                                                .cidr("1.1.2.1/24")
                                                                .routeTableId("main")
                                                                .build()))
                                                .build(),
                                        VpcContextSubnetGroup.builder()
                                                .name("public-2")
                                                .type(VpcSubnetGroupType.PUBLIC)
                                                .subnets(ImmutableList.of(
                                                        VpcContextSubnet.builder()
                                                                .subnetId("public-subnet-2")
                                                                .availabilityZone("az2")
                                                                .cidr("1.1.3.1/24")
                                                                .routeTableId("main")
                                                                .build()))
                                                .build()))
                                .build(),
                        null
                },
        };
    }

    private JsonArray jsonArray() {
        return JsonValue.EMPTY_JSON_ARRAY;
    }

    private JsonArray jsonArray(JsonValue... values) {
        return jsonArray(Arrays.asList(values), JsonArrayBuilder::add);
    }

    private JsonArray jsonArray(String... values) {
        return jsonArray(Arrays.asList(values), JsonArrayBuilder::add);
    }

    private <T> JsonArray jsonArray(List<T> values, BiConsumer<JsonArrayBuilder, T> accumulator) {
        if (values.isEmpty()) {
            return jsonArray();
        }

        return values.stream()
                .collect(Collector.of(
                        Json::createArrayBuilder,
                        accumulator,
                        JsonArrayBuilder::addAll,
                        JsonArrayBuilder::build
                ));
    }

    @Test(dataProvider = "testDataProvider")
    public void test(VpcContextQuery properties, ClientMockData data, VpcContext expectedValue, Class<? extends Exception> exceptionType) {
        Ec2Client ec2Client = mock(Ec2Client.class);
        when(ec2Client.describeVpcs(any(DescribeVpcsRequest.class)))
                .thenReturn(DescribeVpcsResponse.builder().vpcs(data.getVpcs()).build());
        when(ec2Client.describeVpnGateways(any(DescribeVpnGatewaysRequest.class)))
                .thenAnswer(new DescribeVpnGatewaysResponseAnswer(data));
        when(ec2Client.describeRouteTables(any(DescribeRouteTablesRequest.class)))
                .thenAnswer(new DescribeRouteTablesResponseAnswer(data));
        when(ec2Client.describeSubnets(any(DescribeSubnetsRequest.class)))
                .thenAnswer(new DescribeSubnetsResponseAnswer(data));

        AwsClientProvider clientProvider = mock(AwsClientProvider.class);
        when(clientProvider.getClient(any(), any()))
                .thenReturn(ec2Client);

        VpcNetworkContextProviderMapper contextProvider = new VpcNetworkContextProviderMapper(clientProvider);
        if (exceptionType != null) {
            try {
                contextProvider.getContextValue(properties);
                Assert.fail("The context provider is expected to throw " + exceptionType.getSimpleName() + " exception");
            } catch (Exception exception) {
                Assert.assertEquals(exception.getClass(), exceptionType);
            }
        } else {
            Object contextValue = contextProvider.getContextValue(properties);
            Assert.assertEquals(contextValue, expectedValue);
        }

    }

    private Vpc vpc(String id, String cidrBlock) {
        return Vpc.builder()
                .vpcId(id)
                .cidrBlock(cidrBlock)
                .build();
    }

    private VpnGateway vpnGateway(String id, String vpcId) {
        return VpnGateway.builder()
                .vpnGatewayId(id)
                .vpcAttachments(VpcAttachment.builder()
                        .vpcId(vpcId)
                        .build())
                .build();
    }

    private Tag tag(String key, String value) {
        return Tag.builder()
                .key(key)
                .value(value)
                .build();
    }

    private RouteTableAssociation routeTableAssociation(String subnetId) {
        return RouteTableAssociation.builder()
                .subnetId(subnetId)
                .build();
    }

    private RouteTableAssociation mainRouteTableAssociation() {
        return RouteTableAssociation.builder()
                .main(true)
                .build();
    }

    private Subnet subnet(String id, String vpcId, String availabilityZone, Boolean mapPublicIpOnLaunch) {
        return Subnet.builder()
                .vpcId(vpcId)
                .subnetId(id)
                .availabilityZone(availabilityZone)
                .mapPublicIpOnLaunch(mapPublicIpOnLaunch)
                .build();
    }

    private Route route(String destinationCidrBlock, String gatewayId) {
        return Route.builder()
                .destinationCidrBlock(destinationCidrBlock)
                .gatewayId(gatewayId)
                .build();
    }

    private static class ClientMockData {

        private final List<Vpc> vpcs;
        private final List<VpnGateway> vpnGateways;
        private final List<RouteTable> routeTables;
        private final List<Subnet> subnets;

        private ClientMockData(List<Vpc> vpcs, List<VpnGateway> vpnGateways, List<RouteTable> routeTables, List<Subnet> subnets) {
            this.vpcs = vpcs;
            this.vpnGateways = vpnGateways;
            this.routeTables = routeTables;
            this.subnets = subnets;
        }

        public List<Vpc> getVpcs() {
            return vpcs;
        }

        public List<VpnGateway> getVpnGateways() {
            return vpnGateways;
        }

        public List<RouteTable> getRouteTables() {
            return routeTables;
        }

        public List<Subnet> getSubnets() {
            return subnets;
        }

        public ClientMockData withVpc(Vpc vpc) {
            return new ClientMockData(append(vpcs, vpc), vpnGateways, routeTables, subnets);
        }

        public ClientMockData withVpnGateway(VpnGateway vpnGateway) {
            return new ClientMockData(vpcs, append(vpnGateways, vpnGateway), routeTables, subnets);
        }

        public ClientMockData withRouteTable(RouteTable routeTable) {
            return new ClientMockData(vpcs, vpnGateways, append(routeTables, routeTable), subnets);
        }

        public ClientMockData withSubnet(Subnet subnet) {
            return new ClientMockData(vpcs, vpnGateways, routeTables, append(subnets, subnet));
        }

        public static ClientMockData get() {
            return new ClientMockData(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
        }

        private <T> List<T> append(List<T> values, T value) {
            return Streams.concat(values.stream(), Stream.of(value))
                    .collect(Collectors.toList());
        }
    }

    private static class DescribeVpnGatewaysResponseAnswer implements Answer<DescribeVpnGatewaysResponse> {

        private final ClientMockData data;

        public DescribeVpnGatewaysResponseAnswer(ClientMockData data) {
            this.data = data;
        }

        @Override
        public DescribeVpnGatewaysResponse answer(InvocationOnMock invocation) throws Throwable {
            DescribeVpnGatewaysRequest request = invocation.getArgument(0, DescribeVpnGatewaysRequest.class);
            List<VpnGateway> vpnGateways = request.filters().stream()
                    .filter(f -> f.name().equals("attachment.vpc-id"))
                    .map(filter -> filter.values().get(0))
                    .map(vpcId -> data.getVpnGateways().stream()
                            .filter(vpnGateway -> vpnGateway.vpcAttachments().stream()
                                    .anyMatch(attachment -> attachment.vpcId().equals(vpcId)))
                            .collect(Collectors.toList()))
                    .findAny()
                    .orElseGet(data::getVpnGateways);

            return DescribeVpnGatewaysResponse.builder()
                    .vpnGateways(vpnGateways)
                    .build();
        }
    }

    private static class DescribeRouteTablesResponseAnswer implements Answer<DescribeRouteTablesResponse> {
        private final ClientMockData data;

        public DescribeRouteTablesResponseAnswer(ClientMockData data) {
            this.data = data;
        }

        @Override
        public DescribeRouteTablesResponse answer(InvocationOnMock invocation) throws Throwable {
            DescribeRouteTablesRequest request = invocation.getArgument(0, DescribeRouteTablesRequest.class);
            List<RouteTable> routeTables = request.filters().stream()
                    .filter(filter -> filter.name().equals("vpc-id"))
                    .map(filter -> filter.values().get(0))
                    .map(vpcId -> data.getRouteTables().stream()
                            .filter(routeTable -> vpcId.equals(routeTable.vpcId()))
                            .collect(Collectors.toList()))
                    .findAny()
                    .orElseGet(data::getRouteTables);

            return DescribeRouteTablesResponse.builder()
                    .routeTables(routeTables)
                    .build();
        }
    }

    private static class DescribeSubnetsResponseAnswer implements Answer<DescribeSubnetsResponse> {

        private final ClientMockData data;

        public DescribeSubnetsResponseAnswer(ClientMockData data) {
            this.data = data;
        }

        @Override
        public DescribeSubnetsResponse answer(InvocationOnMock invocation) throws Throwable {
            DescribeSubnetsRequest request = invocation.getArgument(0, DescribeSubnetsRequest.class);
            List<Subnet> subnets = request.filters().stream()
                    .filter(filter -> filter.name().equals("vpc-id"))
                    .map(filter -> filter.values().get(0))
                    .map(vpcId -> data.getSubnets().stream()
                            .filter(subnet -> vpcId.equals(subnet.vpcId()))
                            .collect(Collectors.toList()))
                    .findAny()
                    .orElseGet(data::getSubnets);

            return DescribeSubnetsResponse.builder()
                    .subnets(subnets)
                    .build();
        }
    }
}
