/*
 * =========================================================================================
 * Copyright © 2013-2014 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.datadog

import akka.actor._
import kamon.Kamon
import kamon.metric._
import kamon.metrics._
import kamon.metrics.CPUMetrics
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import akka.event.Logging
import java.net.InetSocketAddress

object Datadog extends ExtensionId[DatadogExtension] with ExtensionIdProvider {
  override def lookup(): ExtensionId[_ <: Extension] = Datadog
  override def createExtension(system: ExtendedActorSystem): DatadogExtension = new DatadogExtension(system)

  trait MetricKeyGenerator {
    def generateKey(groupIdentity: MetricGroupIdentity, metricIdentity: MetricIdentity): String
  }
}

class DatadogExtension(system: ExtendedActorSystem) extends Kamon.Extension {
  val log = Logging(system, classOf[DatadogExtension])
  log.info("Starting the Kamon(Datadog) extension")

  private val datadogConfig = system.settings.config.getConfig("kamon.datadog")

  val datadogHost = new InetSocketAddress(datadogConfig.getString("hostname"), datadogConfig.getInt("port"))
  val flushInterval = datadogConfig.getMilliseconds("flush-interval")
  val maxPacketSizeInBytes = datadogConfig.getBytes("max-packet-size")
  val tickInterval = system.settings.config.getMilliseconds("kamon.metrics.tick-interval")

  val datadogMetricsListener = buildMetricsListener(tickInterval, flushInterval)

  // Subscribe to Actors
  val includedActors = datadogConfig.getStringList("includes.actor").asScala
  for (actorPathPattern ← includedActors) {
    Kamon(Metrics)(system).subscribe(ActorMetrics, actorPathPattern, datadogMetricsListener, permanently = true)
  }

  // Subscribe to Traces
  val includedTraces = datadogConfig.getStringList("includes.trace").asScala
  for (tracePathPattern ← includedTraces) {
    Kamon(Metrics)(system).subscribe(TraceMetrics, tracePathPattern, datadogMetricsListener, permanently = true)
  }

  // Subscribe to Dispatchers
  val includedDispatchers = datadogConfig.getStringList("includes.dispatcher").asScala
  for (dispatcherPathPattern ← includedDispatchers) {
    Kamon(Metrics)(system).subscribe(DispatcherMetrics, dispatcherPathPattern, datadogMetricsListener, permanently = true)
  }

  // Subscribe to SystemMetrics
  val includeSystemMetrics = datadogConfig.getBoolean("report-system-metrics")
  if (includeSystemMetrics) {
    List(CPUMetrics, ProcessCPUMetrics, MemoryMetrics, NetworkMetrics, GCMetrics, HeapMetrics) foreach { metric ⇒
      Kamon(Metrics)(system).subscribe(metric, "*", datadogMetricsListener, permanently = true)
    }
  }

  def buildMetricsListener(tickInterval: Long, flushInterval: Long): ActorRef = {
    assert(flushInterval >= tickInterval, "Datadog flush-interval needs to be equal or greater to the tick-interval")

    val metricsSender = system.actorOf(DatadogMetricsSender.props(datadogHost, maxPacketSizeInBytes), "datadog-metrics-sender")
    if (flushInterval == tickInterval) {
      // No need to buffer the metrics, let's go straight to the metrics sender.
      metricsSender
    } else {
      system.actorOf(TickMetricSnapshotBuffer.props(flushInterval.toInt.millis, metricsSender), "datadog-metrics-buffer")
    }
  }
}
