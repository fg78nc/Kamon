/* =========================================================================================
 * Copyright © 2013-2017 the kamon project <http://kamon.io/>
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

package kamon
package metric


import java.util.concurrent.TimeUnit

import com.typesafe.config.Config
import kamon.metric.InstrumentFactory.CustomInstrumentSettings
import java.time.Duration


private[kamon] class InstrumentFactory private (defaultHistogramDynamicRange: DynamicRange, defaultRangeSamplerDynamicRange: DynamicRange,
    defaultRangeSamplerSampleInterval: Duration, customSettings: Map[String, CustomInstrumentSettings]) {

  def buildHistogram(dynamicRange: Option[DynamicRange])(name: String, tags: Map[String, String], unit: MeasurementUnit): AtomicHdrHistogram =
    new AtomicHdrHistogram(name, tags, unit, instrumentDynamicRange(name, dynamicRange.getOrElse(defaultHistogramDynamicRange)))

  def buildRangeSampler(dynamicRange: Option[DynamicRange], sampleInterval: Option[Duration])
      (name: String, tags: Map[String, String], unit: MeasurementUnit): SimpleRangeSampler =
    new SimpleRangeSampler(
      name,
      tags,
      buildHistogram(dynamicRange.orElse(Some(defaultRangeSamplerDynamicRange)))(name, tags, unit),
      instrumentSampleInterval(name, sampleInterval.getOrElse(defaultRangeSamplerSampleInterval)))

  def buildGauge(name: String, tags: Map[String, String], unit: MeasurementUnit): AtomicLongGauge =
    new AtomicLongGauge(name, tags, unit)

  def buildCounter(name: String, tags: Map[String, String], unit: MeasurementUnit): LongAdderCounter =
    new LongAdderCounter(name, tags, unit)


  private def instrumentDynamicRange(instrumentName: String, dynamicRange: DynamicRange): DynamicRange =
    customSettings.get(instrumentName).fold(dynamicRange) { cs =>
      overrideDynamicRange(dynamicRange, cs)
    }

  private def instrumentSampleInterval(instrumentName: String, sampleInterval: Duration): Duration =
    customSettings.get(instrumentName).fold(sampleInterval) { cs =>
      cs.sampleInterval.getOrElse(sampleInterval)
    }

  private def overrideDynamicRange(defaultDynamicRange: DynamicRange, customSettings: CustomInstrumentSettings): DynamicRange =
    DynamicRange(
      customSettings.lowestDiscernibleValue.getOrElse(defaultDynamicRange.lowestDiscernibleValue),
      customSettings.highestTrackableValue.getOrElse(defaultDynamicRange.highestTrackableValue),
      customSettings.significantValueDigits.getOrElse(defaultDynamicRange.significantValueDigits)
    )
}

object InstrumentFactory {

  case class InstrumentType(name: String)

  object InstrumentTypes {
    val Histogram    = InstrumentType("Histogram")
    val RangeSampler = InstrumentType("RangeSampler")
    val Counter      = InstrumentType("Counter")
    val Gauge        = InstrumentType("Gauge")
  }

  def fromConfig(config: Config): InstrumentFactory = {
    val factoryConfig = config.getConfig("kamon.metric.instrument-factory")
    val histogramDynamicRange = readDynamicRange(factoryConfig.getConfig("default-settings.histogram"))
    val rangeSamplerDynamicRange = readDynamicRange(factoryConfig.getConfig("default-settings.range-sampler"))
    val rangeSamplerSampleInterval = factoryConfig.getDuration("default-settings.range-sampler.sample-interval")

    val customSettings = factoryConfig.getConfig("custom-settings")
      .configurations
      .filter(nonEmptySection)
      .map(readCustomInstrumentSettings)

    new InstrumentFactory(histogramDynamicRange, rangeSamplerDynamicRange, rangeSamplerSampleInterval, customSettings)
  }

  private def nonEmptySection(entry: (String, Config)): Boolean = entry match {
    case (_, config) => config.topLevelKeys.nonEmpty
  }

  private def readCustomInstrumentSettings(entry: (String, Config)): (String, CustomInstrumentSettings) = {
    val (metricName, metricConfig) = entry
    val customSettings = CustomInstrumentSettings(
      if (metricConfig.hasPath("lowest-discernible-value")) Some(metricConfig.getLong("lowest-discernible-value")) else None,
      if (metricConfig.hasPath("highest-trackable-value")) Some(metricConfig.getLong("highest-trackable-value")) else None,
      if (metricConfig.hasPath("significant-value-digits")) Some(metricConfig.getInt("significant-value-digits")) else None,
      if (metricConfig.hasPath("sample-interval")) Some(metricConfig.getDuration("sample-interval")) else None
    )
    metricName -> customSettings
  }

  private def readDynamicRange(config: Config): DynamicRange =
    DynamicRange(
      lowestDiscernibleValue = config.getLong("lowest-discernible-value"),
      highestTrackableValue = config.getLong("highest-trackable-value"),
      significantValueDigits = config.getInt("significant-value-digits")
    )

  private case class CustomInstrumentSettings(
    lowestDiscernibleValue: Option[Long],
    highestTrackableValue: Option[Long],
    significantValueDigits: Option[Int],
    sampleInterval: Option[Duration]
  )
}