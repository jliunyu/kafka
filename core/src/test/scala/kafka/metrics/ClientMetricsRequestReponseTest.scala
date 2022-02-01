package kafka.metrics

import kafka.metrics.ClientMetricsTestUtils.{createCMSubscriptionGroup, getCM}
import kafka.metrics.clientmetrics.ClientMetricsConfig.ClientMetrics
import kafka.metrics.clientmetrics.{ClientMetricsConfig, CmClientInformation}
import kafka.server.ClientMetricsManager
import org.apache.kafka.common.Uuid
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.requests.{GetTelemetrySubscriptionRequest, GetTelemetrySubscriptionResponse}
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.{AfterEach, Test}

import java.util.Properties

class ClientMetricsRequestResponseTest {

  @AfterEach
  def cleanup(): Unit = {
    ClientMetricsConfig.clearClientSubscriptions()
    getCM.clearCache()
  }

  private def sendGetSubscriptionRequest(clientInfo: CmClientInformation,
                                         id: Uuid = Uuid.ZERO_UUID): GetTelemetrySubscriptionResponse = {
    val request = new GetTelemetrySubscriptionRequest.Builder(id).build(0);
    getCM.processGetSubscriptionRequest(request, clientInfo, 20)
  }

  @Test def testGetClientMetricsRequestAndResponse(): Unit = {
    val sgroup1 = createCMSubscriptionGroup("cm_1")
    assertTrue(sgroup1 != null)

    val clientInfo = CmClientInformation("testClient1", "clientId3", "Java", "11.1.0", "192.168.1.7", "9093")
    val response = sendGetSubscriptionRequest(clientInfo).data()
    assertTrue(response != null)
    val clientInstanceId = response.clientInstanceId()

    // verify all the parameters ..
    assertTrue(clientInstanceId != Uuid.ZERO_UUID)
    val cmClient = getCM.getClientInstance(clientInstanceId)
    assertTrue(cmClient != null)

    assertTrue(response.throttleTimeMs() == 20)
    assertTrue(response.deltaTemporality() == true)
    assertTrue(response.pushIntervalMs() == cmClient.getPushIntervalMs)
    assertTrue(response.subscriptionId() == cmClient.getSubscriptionId)

    assertTrue(response.requestedMetrics().size == cmClient.getMetrics.size)
    response.requestedMetrics().forEach(x => assertTrue(cmClient.getMetrics.contains(x)))

    assertTrue(response.acceptedCompressionTypes().size() == ClientMetricsManager.getSupportedCompressionTypes.size)
    response.acceptedCompressionTypes().forEach(x =>
      assertTrue(ClientMetricsManager.getSupportedCompressionTypes.contains(x)))
  }

  @Test def testRequestAndResponseWithNoMatchingMetrics(): Unit = {
    val sgroup1 = createCMSubscriptionGroup("cm_2")
    assertTrue(sgroup1 != null)

    // Create a python client that do not have any matching subscriptions.
    val clientInfo = CmClientInformation("testClient1", "clientId3", "Python", "11.1.0", "192.168.1.7", "9093")
    var response = sendGetSubscriptionRequest(clientInfo).data()
    var cmClient = getCM.getClientInstance(response.clientInstanceId())
    val clientInstanceId = response.clientInstanceId()

    // Push interval must be set to the default push interval and requested metrics list should be empty
    assertTrue(response.pushIntervalMs() == ClientMetrics.DEFAULT_PUSH_INTERVAL &&
               response.pushIntervalMs() != sgroup1.getPushIntervalMs)
    assertTrue(response.subscriptionId() == cmClient.getSubscriptionId)
    assertTrue(response.requestedMetrics().isEmpty)

    // Now create a client subscription with client id that matches with the client.
    val props = new Properties()
    val clientMatch = List(s"${CmClientInformation.CLIENT_SOFTWARE_NAME}=Python",
                           s"${CmClientInformation.CLIENT_SOFTWARE_VERSION}=11.1.*")
    props.put(ClientMetricsConfig.ClientMetrics.ClientMatchPattern, clientMatch.mkString(","))
    val sgroup2 = createCMSubscriptionGroup("cm_2", props)
    assertTrue(sgroup2 != null)

    // should have got the positive response with all the valid parameters
    response = sendGetSubscriptionRequest(clientInfo, clientInstanceId).data()
    cmClient = getCM.getClientInstance(clientInstanceId)
    assertTrue(response.pushIntervalMs() == sgroup2.getPushIntervalMs)
    assertTrue(response.subscriptionId() == cmClient.getSubscriptionId)
    assertTrue(!response.requestedMetrics().isEmpty)
    response.requestedMetrics().forEach(x => assertTrue(cmClient.getMetrics.contains(x)))
  }

  @Test def testRequestWithAllMetricsSubscription(): Unit = {
    val clientInfo = CmClientInformation("testClient1", "clientId3", "Java", "11.1.0", "192.168.1.7", "9093")

    // Add first group with default metrics.
    createCMSubscriptionGroup("group1")

    // Add second group that contains allMetrics flag set to true
    val props = new Properties
    props.put(ClientMetricsConfig.ClientMetrics.AllMetricsFlag, true)
    val sgroup1 = createCMSubscriptionGroup("cm_all_metrics", props)
    assertTrue(sgroup1 != null)

    val response = sendGetSubscriptionRequest(clientInfo).data()

    // verify all the parameters ..
    assertTrue(response.clientInstanceId() != Uuid.ZERO_UUID)
    val cmClient = getCM.getClientInstance(response.clientInstanceId())
    assertTrue(cmClient != null)
    assertTrue(response.pushIntervalMs() == cmClient.getPushIntervalMs)
    assertTrue(response.subscriptionId() == cmClient.getSubscriptionId)
    assertTrue(response.requestedMetrics().size() == 1)
    assertTrue(response.requestedMetrics().get(0).isEmpty)
  }

  @Test def testRequestWithDisabledClient(): Unit = {
    val clientInfo = CmClientInformation("testClient5", "clientId5", "Java", "11.1.0", "192.168.1.7", "9093")

    val sgroup1 = createCMSubscriptionGroup("cm_4")
    assertTrue(sgroup1 != null)

    // Submit a request to get the subscribed metrics
    var response = sendGetSubscriptionRequest(clientInfo).data()
    val clientInstanceId = response.clientInstanceId()
    var cmClient = getCM.getClientInstance(clientInstanceId)
    assertTrue(cmClient != null)

    val oldSubscriptionId = response.subscriptionId()
    assertTrue(response.pushIntervalMs() == sgroup1.getPushIntervalMs)
    assertTrue(response.subscriptionId() == cmClient.getSubscriptionId)
    response.requestedMetrics().forEach(x => assertTrue(sgroup1.getSubscribedMetrics.contains(x)))

    // Now create a new client subscription with push interval set to 0.
    val props = new Properties()
    props.put(ClientMetrics.PushIntervalMs, 0.toString)
    val sgroup2 = createCMSubscriptionGroup("cm_update_2_disable", props)
    assertTrue(sgroup2 != null)

    // should have got the invalid response with empty metrics list.
    // set the client instance id which is obtained in earlier request.
    val res = sendGetSubscriptionRequest(clientInfo, clientInstanceId)
    assertTrue(res.error() == Errors.INVALID_CONFIG)
    cmClient = getCM.getClientInstance(clientInstanceId)
    assertTrue(cmClient != null)

    response = res.data()
    assertTrue(response.pushIntervalMs() == 0)
    assertTrue(response.requestedMetrics().isEmpty)
    assertTrue(oldSubscriptionId != response.subscriptionId())
    assertTrue(response.subscriptionId() == cmClient.getSubscriptionId)
  }
}
