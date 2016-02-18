package com.linkedin.datastream.server;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.avro.Schema;
import org.apache.avro.util.Utf8;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.linkedin.datastream.DatastreamRestClient;
import com.linkedin.datastream.common.AvroUtils;
import com.linkedin.datastream.common.Datastream;
import com.linkedin.datastream.common.DatastreamEvent;
import com.linkedin.datastream.common.DatastreamException;
import com.linkedin.datastream.common.DatastreamUtils;
import com.linkedin.datastream.common.PollUtils;
import com.linkedin.datastream.common.zk.ZkClient;
import com.linkedin.datastream.connectors.DummyBootstrapConnector;
import com.linkedin.datastream.connectors.DummyBootstrapConnectorFactory;
import com.linkedin.datastream.connectors.DummyConnector;
import com.linkedin.datastream.connectors.DummyConnectorFactory;
import com.linkedin.datastream.connectors.file.FileConnector;
import com.linkedin.datastream.connectors.file.FileConnectorFactory;
import com.linkedin.datastream.kafka.EmbeddedZookeeperKafkaCluster;
import com.linkedin.datastream.kafka.KafkaDestination;
import com.linkedin.datastream.server.api.schemaregistry.SchemaRegistryException;
import com.linkedin.datastream.server.api.schemaregistry.SchemaRegistryProvider;
import com.linkedin.datastream.server.api.schemaregistry.SchemaRegistryProviderFactory;
import com.linkedin.datastream.server.assignment.BroadcastStrategy;
import com.linkedin.datastream.server.assignment.LoadbalancingStrategy;
import com.linkedin.datastream.server.zk.KeyBuilder;
import com.linkedin.datastream.testutil.DatastreamTestUtils;
import com.linkedin.datastream.testutil.TestUtils;


@Test(singleThreaded = true)
public class TestDatastreamServer {
  private static final Logger LOG = LoggerFactory.getLogger(TestDatastreamServer.class.getName());

  public static final String LOADBALANCING_STRATEGY = LoadbalancingStrategy.class.getTypeName();
  public static final String BROADCAST_STRATEGY = BroadcastStrategy.class.getTypeName();
  public static final String DUMMY_CONNECTOR = DummyConnector.CONNECTOR_TYPE;
  public static final String DUMMY_BOOTSTRAP_CONNECTOR = DummyBootstrapConnector.CONNECTOR_TYPE;
  public static final String FILE_CONNECTOR = FileConnector.CONNECTOR_TYPE;


  private EmbeddedDatastreamCluster _datastreamCluster;

  public static EmbeddedDatastreamCluster initializeTestDatastreamServerWithBootstrap() throws Exception {
    Map<String, Properties> connectorProperties = new HashMap<>();
    connectorProperties.put(DUMMY_CONNECTOR, getDummyConnectorProperties(true));
    connectorProperties.put(DUMMY_BOOTSTRAP_CONNECTOR, getBootstrapConnectorProperties());
    return EmbeddedDatastreamCluster.newTestDatastreamCluster(new EmbeddedZookeeperKafkaCluster(), connectorProperties,
        new Properties());
  }

  private static Properties getBootstrapConnectorProperties() {
    Properties props = new Properties();
    props.put(DatastreamServer.CONFIG_CONNECTOR_ASSIGNMENT_STRATEGY, BROADCAST_STRATEGY);
    props.put(DatastreamServer.CONFIG_CONNECTOR_FACTORY_CLASS_NAME, DummyBootstrapConnectorFactory.class.getTypeName());
    return props;
  }

  public static EmbeddedDatastreamCluster initializeTestDatastreamServerWithDummyConnector(Properties override)
      throws Exception {
    Map<String, Properties> connectorProperties = new HashMap<>();
    connectorProperties.put(DUMMY_CONNECTOR, getDummyConnectorProperties(false));
    EmbeddedDatastreamCluster datastreamKafkaCluster =
        EmbeddedDatastreamCluster.newTestDatastreamCluster(new EmbeddedZookeeperKafkaCluster(), connectorProperties,
            override);
    return datastreamKafkaCluster;
  }

  private static Properties getDummyConnectorProperties(boolean boostrap) {
    Properties props = new Properties();
    props.put(DatastreamServer.CONFIG_CONNECTOR_ASSIGNMENT_STRATEGY, BROADCAST_STRATEGY);
    props.put(DatastreamServer.CONFIG_CONNECTOR_FACTORY_CLASS_NAME, DummyConnectorFactory.class.getTypeName());
    if (boostrap) {
      props.put(DatastreamServer.CONFIG_CONNECTOR_BOOTSTRAP_TYPE, DUMMY_BOOTSTRAP_CONNECTOR);
    }
    props.put("dummyProperty", "dummyValue");
    return props;
  }


  private EmbeddedDatastreamCluster initializeTestDatastreamServerWithFileConnector(int numServers, String strategy)
      throws IOException, DatastreamException {
    return initializeTestDatastreamServerWithFileConnector(numServers, strategy, 1);
  }


  private EmbeddedDatastreamCluster initializeTestDatastreamServerWithFileConnector(int numServers,
                                                                                    String strategy, int numDestinationPartitions)
      throws IOException, DatastreamException {
    Map<String, Properties> connectorProperties = new HashMap<>();
    connectorProperties.put(FILE_CONNECTOR, getTestConnectorProperties(strategy));
    connectorProperties.get(FILE_CONNECTOR).put(FileConnector.CFG_NUM_PARTITIONS, String.valueOf(numDestinationPartitions));
    Properties override = new Properties();
    override.put(CoordinatorConfig.CONFIG_SCHEMA_REGISTRY_PROVIDER_FACTORY, MockSchemaRegistryProviderFactory.class.getTypeName());
    EmbeddedDatastreamCluster datastreamKafkaCluster =
        EmbeddedDatastreamCluster.newTestDatastreamCluster(new EmbeddedZookeeperKafkaCluster(), connectorProperties,
            override, numServers);
    return datastreamKafkaCluster;
  }

  @Test
  public void testDatastreamServerBasics() throws Exception {
    initializeTestDatastreamServerWithDummyConnector(null);
    initializeTestDatastreamServerWithBootstrap();
    _datastreamCluster = initializeTestDatastreamServerWithFileConnector(2, BROADCAST_STRATEGY);
  }

  @Test
  public void testCreateTwoDatastreamOfFileConnectorProduceEventsReceiveEvents() throws Exception {
    int numberOfPartitions = 5;
    _datastreamCluster = initializeTestDatastreamServerWithFileConnector(1, BROADCAST_STRATEGY, numberOfPartitions);
    int totalEvents = 10;
    _datastreamCluster.startup();
    String fileName1 = "/tmp/testFile1_" + UUID.randomUUID().toString();
    Datastream fileDatastream1 = createFileDatastream(fileName1);
    Assert.assertEquals((int) fileDatastream1.getDestination().getPartitions(), numberOfPartitions);

    Collection<String> eventsWritten1 = TestUtils.generateStrings(totalEvents);
    FileUtils.writeLines(new File(fileName1), eventsWritten1);


    Collection<String> eventsReceived1 = readFileDatastreamEvents(fileDatastream1, totalEvents);

    LOG.info("Events Received " + eventsReceived1);
    LOG.info("Events Written to file " + eventsWritten1);

    Assert.assertTrue(eventsReceived1.containsAll(eventsWritten1));

    // Test with the second datastream

    String fileName2 = "/tmp/testFile2_" + UUID.randomUUID().toString();
    Datastream fileDatastream2 = createFileDatastream(fileName2);

    Collection<String> eventsWritten2 = TestUtils.generateStrings(totalEvents);
    FileUtils.writeLines(new File(fileName2), eventsWritten2);

    Collection<String> eventsReceived2 = readFileDatastreamEvents(fileDatastream2, totalEvents);

    LOG.info("Events Received " + eventsReceived2);
    LOG.info("Events Written to file " + eventsWritten2);

    Assert.assertTrue(eventsReceived2.containsAll(eventsWritten2));

    _datastreamCluster.shutdown();
  }

  @Test
  public void testNodeDownOneDatastreamSimpleStrategy() throws Exception {
      _datastreamCluster = initializeTestDatastreamServerWithFileConnector(2, LOADBALANCING_STRATEGY);
    _datastreamCluster.startup();

    List<DatastreamServer> servers = _datastreamCluster.getAllDatastreamServers();
    Assert.assertEquals(servers.size(), 2);
    Assert.assertNotNull(servers.get(0));
    Assert.assertNotNull(servers.get(1));
    DatastreamServer server1 = servers.get(0);
    DatastreamServer server2 = servers.get(1);

    String fileName1 = "/tmp/testFile1_" + UUID.randomUUID().toString();
    Datastream fileDatastream1 = createFileDatastream(fileName1);
    int totalEvents = 10;
    List<String> eventsWritten1 = TestUtils.generateStrings(totalEvents);

    // Write some events and make sure DMS properly produces them
    FileUtils.writeLines(new File(fileName1), eventsWritten1);

    List<String> eventsReceived1 = readFileDatastreamEvents(fileDatastream1, totalEvents);

    LOG.info("Events Received " + eventsReceived1);
    LOG.info("Events Written to file " + eventsWritten1);

    Assert.assertTrue(eventsReceived1.containsAll(eventsWritten1));

    // Ensure 1st instance was assigned the task
    String cluster = _datastreamCluster.getPrimaryDatastreamServerProperties().getProperty(
            DatastreamServer.CONFIG_CLUSTER_NAME);
    ZkClient zkclient = new ZkClient(_datastreamCluster.getZkConnection());
    String instance = server1.getCoordinator().getInstanceName();
    String assignmentPath = KeyBuilder.instanceAssignments(cluster, instance);
    List<String> assignments = zkclient.getChildren(assignmentPath);
    Assert.assertEquals(assignments.size(), 1);

    // Stop 1st instance and wait until its ZK node is gone
    _datastreamCluster.shutdownServer(0);
    String instancesPath = KeyBuilder.liveInstances(cluster);
    Assert.assertTrue(PollUtils.poll(() -> zkclient.getChildren(instancesPath).size() == 1, 100, 5000));

    // Ensure 2nd instance took over the task
    instance = server2.getCoordinator().getInstanceName();
    assignmentPath = KeyBuilder.instanceAssignments(cluster, instance);
    Assert.assertTrue(PollUtils.poll((path) -> zkclient.getChildren(path).size() == 1, 100, 10000, assignmentPath));

    // Wait 3 seconds to allow the connectors to stop the handler and flush the checkpoints
    // Automatic flush period is 1 second by default.
    Thread.sleep(3000);

    // Ensure 2nd instance can read all
    List<String> eventsWritten2 = TestUtils.generateStrings(totalEvents);

    // Append the lines to test checkpoint functionality where 2nd instance should resume from
    // the previous saved checkpoint by the 1st instance before it died.
    FileUtils.writeLines(new File(fileName1), eventsWritten2, true /* append */);

    // Read twice as many events (eventsWritten1 + eventsWritten2) because
    // KafkaTestUtils.readTopic always seeks to the beginning of the topic.
    List<String> eventsReceived2 = readFileDatastreamEvents(fileDatastream1, totalEvents * 2);

    LOG.info("Events Received " + eventsReceived2);
    LOG.info("Events Written to file " + eventsWritten2);

    // If no duplicate events were produced eventsReceived2 should equal eventsWritten1 + eventsWritten2
    // because KafkaTestUtils.readTopic always seeks to the beginning of the topic.
    Assert.assertTrue(eventsReceived2.containsAll(eventsWritten2));
  }

  @Test
  public void testNodeDownOneDatastreamBroadcastStrategy() throws Exception {
    _datastreamCluster = initializeTestDatastreamServerWithFileConnector(2, BROADCAST_STRATEGY);
    _datastreamCluster.startup();

    List<DatastreamServer> servers = _datastreamCluster.getAllDatastreamServers();
    Assert.assertEquals(servers.size(), 2);
    Assert.assertNotNull(servers.get(0));
    Assert.assertNotNull(servers.get(1));
    DatastreamServer server1 = servers.get(0);
    DatastreamServer server2 = servers.get(1);

    String fileName1 = "/tmp/testFile1_" + UUID.randomUUID().toString();
    Datastream fileDatastream1 = createFileDatastream(fileName1);
    int totalEvents = 10;
    List<String> eventsWritten1 = TestUtils.generateStrings(totalEvents);

    // Start with a few events and make sure DMS properly produces them
    FileUtils.writeLines(new File(fileName1), eventsWritten1);

    List<String> eventsReceived1 = readFileDatastreamEvents(fileDatastream1, totalEvents * 2);

    LOG.info("Events Received " + eventsReceived1);
    LOG.info("Events Written to file " + eventsWritten1);

    // Expect two copies of eventsWritten1 given the two instances and BROADCAST strategy
    Map<String, Integer> countMap = new HashMap<>();
    eventsWritten1.forEach(ev -> countMap.put(ev, 2));
    eventsReceived1.forEach(ev -> countMap.put(ev, countMap.getOrDefault(ev, 0) - 1));
    countMap.forEach((k, v) -> Assert.assertEquals(v, (Integer) 0, "incorrect number of " + k + " is read"));

    // Ensure both instances were assigned the task
    String cluster = _datastreamCluster.getPrimaryDatastreamServerProperties().getProperty(
        DatastreamServer.CONFIG_CLUSTER_NAME);
    ZkClient zkclient = new ZkClient(_datastreamCluster.getZkConnection());
    String instance = server1.getCoordinator().getInstanceName();
    String assignmentPath = KeyBuilder.instanceAssignments(cluster, instance);
    List<String> assignments = zkclient.getChildren(assignmentPath);
    Assert.assertEquals(assignments.size(), 1);
    instance = server2.getCoordinator().getInstanceName();
    assignmentPath = KeyBuilder.instanceAssignments(cluster, instance);
    assignments = zkclient.getChildren(assignmentPath);
    Assert.assertEquals(assignments.size(), 1);

    // Stop 1st instance and wait until its ZK node is gone
    _datastreamCluster.shutdownServer(0);
    String instancesPath = KeyBuilder.liveInstances(cluster);
    Assert.assertTrue(PollUtils.poll(() -> zkclient.getChildren(instancesPath).size() == 1, 100, 5000));

    // Ensure 2nd instance still has the task
    instance = server2.getCoordinator().getInstanceName();
    assignmentPath = KeyBuilder.instanceAssignments(cluster, instance);
    Assert.assertTrue(PollUtils.poll((path) -> zkclient.getChildren(path).size() == 1, 100, 10000, assignmentPath));

    // Wait 3 seconds to allow the connectors to stop the handler and flush the checkpoints
    // Automatic flush period is 1 second by default.
    Thread.sleep(3000);

    // Ensure 2nd instance can still produce events
    List<String> eventsWritten2 = TestUtils.generateStrings(totalEvents);

    // Caveat: MUST use appendLines otherwise FileConnector somehow cannot
    // see the newly written lines. This might be due to writeLines overwrites
    // the file. Checking the file creation time does not work because the
    // creation timestamp does not change after writeLines().
    FileUtils.writeLines(new File(fileName1), eventsWritten2, true /* append */);

    // Read three times as many events (eventsWritten1 * 2 + eventsWritten2) because
    // KafkaTestUtils.readTopic always seeks to the beginning of the topic.
    List<String> eventsReceived2 = readFileDatastreamEvents(fileDatastream1, totalEvents * 3);

    LOG.info("Events Received " + eventsReceived2);
    LOG.info("Events Written to file " + eventsWritten2);

    // Expect to see one copy of eventsWritten2 in eventsReceived2
    Map<String, Integer> countMap2 = new HashMap<>();
    eventsWritten2.forEach((ev) -> countMap2.put(ev, 1));
    eventsReceived2.forEach((ev) -> {
      if (countMap2.containsKey(ev)) {
        countMap2.put(ev, countMap2.get(ev) - 1);
      }
    });
    countMap2.forEach((k, v) -> Assert.assertEquals(v, (Integer) 0, "incorrect number of " + k + " is read"));
  }

  // Test is flaky, Need to deflake before enabling it.
  // This test doesn't fail often, Need to run this quite a few times before enabling it
  @Test(enabled = false)
  public void testNodeUpRebalanceTwoDatastreamsSimpleStrategy() throws Exception {
    _datastreamCluster = initializeTestDatastreamServerWithFileConnector(2, LOADBALANCING_STRATEGY);
    _datastreamCluster.startupServer(0);

    List<DatastreamServer> servers = _datastreamCluster.getAllDatastreamServers();
    Assert.assertEquals(servers.size(), 2);
    Assert.assertNotNull(servers.get(0));
    DatastreamServer server1 = servers.get(0);

    String fileName1 = "/tmp/testFile1_" + UUID.randomUUID().toString();
    String fileName2 = "/tmp/testFile2_" + UUID.randomUUID().toString();

    Datastream fileDatastream1 = createFileDatastream(fileName1);
    Datastream fileDatastream2 = createFileDatastream(fileName2);

    int totalEvents = 10;
    List<String> eventsWritten1 = TestUtils.generateStrings(totalEvents);
    List<String> eventsWritten2 = TestUtils.generateStrings(totalEvents);

    FileUtils.writeLines(new File(fileName1), eventsWritten1);
    FileUtils.writeLines(new File(fileName2), eventsWritten2);

    List<String> eventsReceived1 = readFileDatastreamEvents(fileDatastream1, totalEvents);
    List<String> eventsReceived2 = readFileDatastreamEvents(fileDatastream2, totalEvents);

    LOG.info("(1) Events Received " + eventsReceived1);
    LOG.info("(1) Events Written to file " + eventsWritten1);
    LOG.info("(2) Events Received " + eventsReceived2);
    LOG.info("(2) Events Written to file " + eventsWritten2);

    Assert.assertTrue(eventsReceived1.containsAll(eventsWritten1));
    Assert.assertTrue(eventsReceived2.containsAll(eventsWritten2));

    // Ensure 1st instance was assigned both tasks
    String cluster = _datastreamCluster.getPrimaryDatastreamServerProperties().getProperty(
            DatastreamServer.CONFIG_CLUSTER_NAME);
    ZkClient zkclient = new ZkClient(_datastreamCluster.getZkConnection());
    String instance1 = server1.getCoordinator().getInstanceName();
    String assignmentPath = KeyBuilder.instanceAssignments(cluster, instance1);
    List<String> assignments = zkclient.getChildren(assignmentPath);
    Assert.assertEquals(assignments.size(), 2);

    // Start 2nd instance and wait until it shows up in ZK
    _datastreamCluster.startupServer(1);
    DatastreamServer server2 = servers.get(1);
    Assert.assertNotNull(server2);
    String instancesPath = KeyBuilder.liveInstances(cluster);
    Assert.assertTrue(PollUtils.poll(() -> zkclient.getChildren(instancesPath).size() == 2, 100, 5000));

    // Ensure each instance gets one task
    assignmentPath = KeyBuilder.instanceAssignments(cluster, instance1);
    Assert.assertTrue(PollUtils.poll((path) -> zkclient.getChildren(path).size() == 1, 100, 10000, assignmentPath));
    LOG.info("Instance1 got task: " + zkclient.getChildren(assignmentPath));

    String instance2 = server2.getCoordinator().getInstanceName();
    assignmentPath = KeyBuilder.instanceAssignments(cluster, instance2);
    Assert.assertTrue(PollUtils.poll((path) -> zkclient.getChildren(path).size() == 1, 100, 10000, assignmentPath));
    LOG.info("Instance2 got task: " + zkclient.getChildren(assignmentPath));

    // Wait 3 seconds to allow the connectors to stop the handler and flush the checkpoints
    // Automatic flush period is 1 second by default.
    Thread.sleep(3000);

    eventsWritten1 = TestUtils.generateStrings(totalEvents);
    eventsWritten2 = TestUtils.generateStrings(totalEvents);

    FileUtils.writeLines(new File(fileName1), eventsWritten1, true /* append */);
    FileUtils.writeLines(new File(fileName2), eventsWritten2, true /* append */);

    // Read twice as many events because KafkaTestUtils.readTopic always seeks
    // to the beginning of the topic such that previous events are included
    eventsReceived1 = readFileDatastreamEvents(fileDatastream1, totalEvents * 2);
    eventsReceived2 = readFileDatastreamEvents(fileDatastream2, totalEvents * 2);

    LOG.info("(1-NEW) Events Received " + eventsReceived1);
    LOG.info("(1-NEW) Events Written to file " + eventsWritten1);
    LOG.info("(2-NEW) Events Received " + eventsReceived2);
    LOG.info("(2-NEW) Events Written to file " + eventsWritten2);

    Assert.assertTrue(eventsReceived1.containsAll(eventsWritten1));
    Assert.assertTrue(eventsReceived2.containsAll(eventsWritten2));
  }

  private List<String> readFileDatastreamEvents(Datastream datastream, int totalEvents) throws Exception {
    KafkaDestination kafkaDestination =
        KafkaDestination.parseKafkaDestinationUri(datastream.getDestination().getConnectionString());
    final int[] numberOfMessages = { 0 };
    List<String> eventsReceived = new ArrayList<>();
    KafkaTestUtils.readTopic(kafkaDestination.topicName(), 0, _datastreamCluster.getBrokerList(), (key, value) -> {
      DatastreamEvent datastreamEvent = AvroUtils.decodeAvroSpecificRecord(DatastreamEvent.class, value);
      String eventValue = new String(datastreamEvent.payload.array());
      DatastreamUtils.processEventMetadata(datastreamEvent);
      String schemaId = datastreamEvent.metadata.get("PayloadSchemaId").toString();
      LOG.info(String.format("Datastream: %s, Schema Id: %s", datastream, schemaId));
      Assert.assertEquals(schemaId, MockSchemaRegistryProvider.MOCK_SCHEMA_ID);
      eventsReceived.add(eventValue);
      numberOfMessages[0]++;
      return numberOfMessages[0] < totalEvents;
    });

    return eventsReceived;
  }

  private Datastream createFileDatastream(String fileName) throws IOException, DatastreamException {
    File testFile = new File(fileName);
    testFile.createNewFile();
    testFile.deleteOnExit();
    Datastream fileDatastream1 =
        DatastreamTestUtils.createDatastream(FileConnector.CONNECTOR_TYPE, "file_" + testFile.getName(),
            testFile.getAbsolutePath());
    DatastreamRestClient restClient = _datastreamCluster.createDatastreamRestClient();
    restClient.createDatastream(fileDatastream1);
    return getPopulatedDatastream(restClient, fileDatastream1);
  }

  private Datastream getPopulatedDatastream(DatastreamRestClient restClient, Datastream fileDatastream1) {
    Boolean pollResult = PollUtils.poll(() -> {
      Datastream ds = null;
      ds = restClient.getDatastream(fileDatastream1.getName());
      return ds.hasDestination() && ds.getDestination().hasConnectionString() && !ds.getDestination().getConnectionString().isEmpty();
    }, 500, 60000);

    if (pollResult) {
      return restClient.getDatastream(fileDatastream1.getName());
    } else {
      throw new RuntimeException("Destination was not populated before the timeout");
    }
  }

  private Properties getTestConnectorProperties(String strategy) {
    Properties props = new Properties();
    props.put(DatastreamServer.CONFIG_CONNECTOR_ASSIGNMENT_STRATEGY, strategy);
    props.put(DatastreamServer.CONFIG_CONNECTOR_FACTORY_CLASS_NAME, FileConnectorFactory.class.getTypeName());
    return props;
  }
}
