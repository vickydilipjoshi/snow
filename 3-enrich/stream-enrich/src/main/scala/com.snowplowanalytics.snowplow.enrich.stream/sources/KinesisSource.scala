 /*
 * Copyright (c) 2013-2017 Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache
 * License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.
 *
 * See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */
package com.snowplowanalytics
package snowplow
package enrich
package stream
package sources

import java.net.InetAddress
import java.util.{List, UUID}

import scala.util.control.Breaks._
import scala.collection.JavaConversions._
import scala.util.control.NonFatal

import com.amazonaws.services.kinesis.clientlibrary.interfaces._
import com.amazonaws.services.kinesis.clientlibrary.exceptions._
import com.amazonaws.services.kinesis.clientlibrary.lib.worker._
import com.amazonaws.services.kinesis.model.Record
import org.apache.thrift.TDeserializer
import org.slf4j.LoggerFactory

import common.enrichments.EnrichmentRegistry
import iglu.client.Resolver
import model.EnrichConfig
import scalatracker.Tracker
import sinks._

/**
 * Source to read events from a Kinesis stream
 */
class KinesisSource(
  config: EnrichConfig,
  igluResolver: Resolver,
  enrichmentRegistry: EnrichmentRegistry,
  tracker: Option[Tracker]
) extends AbstractSource(config, igluResolver, enrichmentRegistry, tracker) {

  lazy val log = LoggerFactory.getLogger(getClass())

  /**
   * Never-ending processing loop over source stream.
   */
  override def run(): Unit = {
    val workerId = InetAddress.getLocalHost().getCanonicalHostName() + ":" + UUID.randomUUID()
    log.info("Using workerId: " + workerId)

    val kinesisClientLibConfiguration = {
      val kclc = new KinesisClientLibConfiguration(
        config.streams.appName,
        config.streams.in.raw,
        kinesisProvider,
        workerId
      ).withKinesisEndpoint(config.streams.kinesis.streamEndpoint)
        .withMaxRecords(config.streams.kinesis.maxRecords)
        .withRegionName(config.streams.kinesis.region)
        // If the record list is empty, we still check whether it is time to flush the buffer
        .withCallProcessRecordsEvenForEmptyRecordList(true)

      val position = InitialPositionInStream.valueOf(config.streams.kinesis.initialPosition)
      config.streams.kinesis.timestamp.right.toOption
        .filter(_ => position == InitialPositionInStream.AT_TIMESTAMP)
        .map(kclc.withTimestampAtInitialPositionInStream(_))
        .getOrElse(kclc.withInitialPositionInStream(position))
    }

    log.info(s"Running: ${config.streams.appName}.")
    log.info(s"Processing raw input stream: ${config.streams.in.raw}")

    val rawEventProcessorFactory = new RawEventProcessorFactory(
      config,
      sink.get.get // TODO: yech, yech
    )
    val worker = new Worker(
      rawEventProcessorFactory,
      kinesisClientLibConfiguration
    )

    worker.run()
  }

  // Factory needed by the Amazon Kinesis Consumer library to
  // create a processor.
  class RawEventProcessorFactory(config: EnrichConfig, sink: ISink)
      extends IRecordProcessorFactory {
    override def createProcessor: IRecordProcessor = {
      new RawEventProcessor(config, sink);
    }
  }

  // Process events from a Kinesis stream.
  class RawEventProcessor(config: EnrichConfig, sink: ISink)
      extends IRecordProcessor {
    private val thriftDeserializer = new TDeserializer()

    private var kinesisShardId: String = _

    // Backoff and retry settings.
    private val BACKOFF_TIME_IN_MILLIS = 3000L
    private val NUM_RETRIES = 10
    private val CHECKPOINT_INTERVAL_MILLIS = 1000L

    override def initialize(shardId: String) = {
      log.info("Initializing record processor for shard: " + shardId)
      this.kinesisShardId = shardId
    }

    override def processRecords(records: List[Record],
        checkpointer: IRecordProcessorCheckpointer) = {

      if (!records.isEmpty) {
        log.info(s"Processing ${records.size} records from $kinesisShardId")
      }
      val shouldCheckpoint = processRecordsWithRetries(records)

      if (shouldCheckpoint) {
        checkpoint(checkpointer)
      }
    }

    private def processRecordsWithRetries(records: List[Record]): Boolean = {
      try {
        enrichAndStoreEvents(records.map(_.getData.array).toList)
      } catch {
        case NonFatal(e) =>
          // TODO: send an event when something goes wrong here
          log.error(s"Caught throwable while processing records $records", e)
          false
      }
    }

    override def shutdown(checkpointer: IRecordProcessorCheckpointer,
        reason: ShutdownReason) = {
      log.info(s"Shutting down record processor for shard: $kinesisShardId")
      if (reason == ShutdownReason.TERMINATE) {
        checkpoint(checkpointer)
      }
    }

    private def checkpoint(checkpointer: IRecordProcessorCheckpointer) = {
      log.info(s"Checkpointing shard $kinesisShardId")
      breakable {
        for (i <- 0 to NUM_RETRIES-1) {
          try {
            checkpointer.checkpoint()
            break
          } catch {
            case se: ShutdownException =>
              log.error("Caught shutdown exception, skipping checkpoint.", se)
              break
            case e: ThrottlingException =>
              if (i >= (NUM_RETRIES - 1)) {
                log.error(s"Checkpoint failed after ${i+1} attempts.", e)
              } else {
                log.info(s"Transient issue when checkpointing - attempt ${i+1} of "
                  + NUM_RETRIES, e)
              }
            case e: InvalidStateException =>
              log.error("Cannot save checkpoint to the DynamoDB table used by " +
                "the Amazon Kinesis Client Library.", e)
              break
          }
          Thread.sleep(BACKOFF_TIME_IN_MILLIS)
        }
      }
    }
  }
}
