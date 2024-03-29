package org.sunbird.job.spec

import com.google.gson.Gson
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration
import org.apache.flink.streaming.api.functions.source.SourceFunction
import org.apache.flink.streaming.api.functions.source.SourceFunction.SourceContext
import org.apache.flink.test.util.MiniClusterWithClientResource
import org.cassandraunit.CQLDataLoader
import org.cassandraunit.dataset.cql.FileCQLDataSet
import org.cassandraunit.utils.EmbeddedCassandraServerHelper
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.when
import org.sunbird.job.connector.FlinkKafkaConnector
import org.sunbird.job.content.function.CollectionPublishFunction
import org.sunbird.job.content.publish.domain.Event
import org.sunbird.job.content.task.{ContentPublishConfig, ContentPublishStreamTask}
import org.sunbird.job.domain.`object`.{DefinitionCache, ObjectDefinition}
import org.sunbird.job.fixture.EventFixture
import org.sunbird.job.publish.config.PublishConfig
import org.sunbird.job.publish.core.{ExtDataConfig, ObjectData}
import org.sunbird.job.util.{CassandraUtil, CloudStorageUtil, HttpUtil, Neo4JUtil}
import org.sunbird.spec.{BaseMetricsReporter, BaseTestSpec}

import java.text.SimpleDateFormat
import java.util
import java.util.Date

class ContentPublishStreamTaskSpec extends BaseTestSpec {

  implicit val mapTypeInfo: TypeInformation[java.util.Map[String, AnyRef]] = TypeExtractor.getForClass(classOf[java.util.Map[String, AnyRef]])
  implicit val strTypeInfo: TypeInformation[String] = TypeExtractor.getForClass(classOf[String])

  val flinkCluster = new MiniClusterWithClientResource(new MiniClusterResourceConfiguration.Builder()
    .setConfiguration(testConfiguration())
    .setNumberSlotsPerTaskManager(1)
    .setNumberTaskManagers(1)
    .build)
  val mockKafkaUtil: FlinkKafkaConnector = mock[FlinkKafkaConnector](Mockito.withSettings().serializable())
  val config: Config = ConfigFactory.load("test.conf").withFallback(ConfigFactory.systemEnvironment())
  implicit val jobConfig: ContentPublishConfig = new ContentPublishConfig(config)
  var definitionCache = new DefinitionCache()
  implicit val definition: ObjectDefinition = definitionCache.getDefinition("Collection", jobConfig.schemaSupportVersionMap.getOrElse("collection", "1.0").asInstanceOf[String], jobConfig.definitionBasePath)
  implicit val readerConfig: ExtDataConfig = ExtDataConfig(jobConfig.hierarchyKeyspaceName, jobConfig.hierarchyTableName, definition.getExternalPrimaryKey, definition.getExternalProps)

  val mockHttpUtil = mock[HttpUtil](Mockito.withSettings().serializable())
  implicit val mockNeo4JUtil: Neo4JUtil = mock[Neo4JUtil](Mockito.withSettings().serializable())
  var cassandraUtil: CassandraUtil = _
  val publishConfig: PublishConfig = new PublishConfig(config, "")
  val cloudStorageUtil: CloudStorageUtil = new CloudStorageUtil(publishConfig)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    EmbeddedCassandraServerHelper.startEmbeddedCassandra(80000L)
    cassandraUtil = new CassandraUtil(jobConfig.cassandraHost, jobConfig.cassandraPort)
    val session = cassandraUtil.session
    val dataLoader = new CQLDataLoader(session)
    dataLoader.load(new FileCQLDataSet(getClass.getResource("/test.cql").getPath, true, true))
    flinkCluster.before()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    try {
      EmbeddedCassandraServerHelper.cleanEmbeddedCassandra()
    } catch {
      case ex: Exception => {
      }
    }
    flinkCluster.after()
  }

  def initialize(): Unit = {
    when(mockKafkaUtil.kafkaJobRequestSource[Event](jobConfig.kafkaInputTopic)).thenReturn(new ContentPublishEventSource)
  }

  def getTimeStamp: String = {
    val sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    sdf.format(new Date())
  }


  "fetchDialListForContextUpdate" should "fetch the list of added and removed QR codes" in {
    val nodeObj = new ObjectData("do_21354027142511820812318.img", Map("objectType" -> "Collection", "identifier" -> "do_21354027142511820812318", "name" -> "DialCodeHierarchy", "lastPublishedOn" -> getTimeStamp, "lastUpdatedOn" -> getTimeStamp, "status" -> "Draft", "pkgVersion" -> 1.asInstanceOf[Number], "versionKey" -> "1652871771396", "channel" -> "0126825293972439041", "contentType" -> "TextBook"), Some(Map()), Some(Map()))
    val DIALListMap = new CollectionPublishFunction(jobConfig, mockHttpUtil).fetchDialListForContextUpdate(nodeObj)(mockNeo4JUtil, cassandraUtil, readerConfig)
    assert(DIALListMap.nonEmpty)

    val postProcessEvent = new CollectionPublishFunction(jobConfig, mockHttpUtil).getPostProcessEvent(nodeObj, DIALListMap)
    assert(postProcessEvent.nonEmpty)
  }

  ignore should " publish the content " in {
    when(mockNeo4JUtil.getNodeProperties(anyString())).thenReturn(new util.HashMap[String, AnyRef])
    initialize
    new ContentPublishStreamTask(jobConfig, mockKafkaUtil, mockHttpUtil).process()
    BaseMetricsReporter.gaugeMetrics(s"${jobConfig.jobName}.${jobConfig.totalEventsCount}").getValue() should be(1)
    BaseMetricsReporter.gaugeMetrics(s"${jobConfig.jobName}.${jobConfig.contentPublishEventCount}").getValue() should be(1)
  }
}

private class ContentPublishEventSource extends SourceFunction[Event] {

  override def run(ctx: SourceContext[Event]) {
    ctx.collect(jsonToEvent(EventFixture.PDF_EVENT1))
  }

  override def cancel() = {}

  def jsonToEvent(json: String): Event = {
    val gson = new Gson()
    val data = gson.fromJson(json, new util.LinkedHashMap[String, Any]().getClass).asInstanceOf[util.Map[String, Any]]
    val metadataMap = data.get("edata").asInstanceOf[util.Map[String, Any]].get("metadata").asInstanceOf[util.Map[String, Any]]
    metadataMap.put("pkgVersion", metadataMap.get("pkgVersion").asInstanceOf[Double].toInt)
    new Event(data, 0, 10)
  }
}
