package ai.catboost.spark.impl

import collection.mutable.HashMap

import java.net._
import java.util.concurrent.{ExecutorCompletionService,Executors}
import java.util.concurrent.atomic.AtomicBoolean

import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.JsonDSL._

import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{Row,SparkSession}
import org.apache.spark.TaskContext

import ru.yandex.catboost.spark.catboost4j_spark.core.src.native_impl._

import ai.catboost.CatBoostError
import ai.catboost.spark._


private[spark] object Worker {
  val usedInCurrentProcess : AtomicBoolean = new AtomicBoolean

  def processPartition(
    trainingDriverListeningAddress: InetSocketAddress,
    catBoostJsonParamsString: String,
    quantizedFeaturesInfo: QuantizedFeaturesInfoPtr,
    threadCount: Int,
    getDataProviderCallback : () => TDataProviderPtr // can return null
  ) = {
    if (!usedInCurrentProcess.compareAndSet(false, true)) {
      throw new CatBoostError("An active CatBoost worker is already present in the current process")
    }

    try {
      // TaskContext.getPartitionId will become invalid when iteration over rows is finished, so save it
      val partitionId = TaskContext.getPartitionId

      var quantizedDataProvider = getDataProviderCallback()

      val partitionSize = if (quantizedDataProvider != null) quantizedDataProvider.GetObjectCount.toInt else 0

      if (partitionSize != 0) {
        native_impl.CreateTrainingDataForWorker(
          partitionId,
          threadCount,
          catBoostJsonParamsString,
          quantizedDataProvider,
          quantizedFeaturesInfo
        )
      }

      val workerPort = TrainingDriver.getWorkerPort()

      val ecs = new ExecutorCompletionService[Unit](Executors.newFixedThreadPool(2))

      val sendWorkerInfoFuture = ecs.submit(
        new Runnable() {
          def run() = {
            TrainingDriver.waitForListeningPortAndSendWorkerInfo(
              trainingDriverListeningAddress,
              partitionId,
              partitionSize,
              workerPort
            )
          }
        },
        ()
      )

      val workerFuture = ecs.submit(
        new Runnable() {
          def run() = {
            if (partitionSize != 0) {
              native_impl.RunWorkerWrapper(threadCount, workerPort)
            }
          }
        },
        ()
      )

      val firstCompletedFuture = ecs.take()

      if (firstCompletedFuture == workerFuture) {
        impl.Helpers.checkOneFutureAndWaitForOther(workerFuture, sendWorkerInfoFuture, "native_impl.RunWorkerWrapper")
      } else { // firstCompletedFuture == sendWorkerInfoFuture
        impl.Helpers.checkOneFutureAndWaitForOther(
          sendWorkerInfoFuture,
          workerFuture,
          "TrainingDriver.waitForListeningPortAndSendWorkerInfo"
        )
      }

    } finally {
      usedInCurrentProcess.set(false)
    }
  }
}


private[spark] class Workers(
  val spark: SparkSession,
  val workerCount: Int,
  val trainingDriverListeningPort: Int,
  val preprocessedTrainPool: Pool,
  val catBoostJsonParams: JObject
) extends Runnable {
  def run() = {
    val trainingDriverListeningAddress = new InetSocketAddress(
      SparkHelpers.getDriverHost(spark),
      trainingDriverListeningPort
    )

    val quantizedFeaturesInfo = preprocessedTrainPool.quantizedFeaturesInfo

    val (trainDataForWorkers, columnIndexMapForWorkers) = DataHelpers.selectColumnsForTrainingAndReturnIndex(
      preprocessedTrainPool,
      includeFeatures = true,
      includeSampleId = (preprocessedTrainPool.pairsData != null)
    )

    val threadCount = SparkHelpers.getThreadCountForTask(spark)

    var catBoostJsonParamsForWorkers = catBoostJsonParams ~ ("thread_count" -> threadCount)

    val executorNativeMemoryLimit = SparkHelpers.getExecutorNativeMemoryLimit(spark)
    if (executorNativeMemoryLimit.isDefined) {
      catBoostJsonParamsForWorkers
        = catBoostJsonParamsForWorkers ~ ("used_ram_limit" -> s"${executorNativeMemoryLimit.get / 1024}KB")
    }

    val catBoostJsonParamsForWorkersString = compact(catBoostJsonParamsForWorkers)

    val dataMetaInfo = preprocessedTrainPool.createDataMetaInfo
    val schemaForWorkers = trainDataForWorkers.schema

    if (preprocessedTrainPool.pairsData != null) {
      val cogroupedTrainData = DataHelpers.getCogroupedMainAndPairsRDD(
        trainDataForWorkers, 
        columnIndexMapForWorkers("groupId"), 
        preprocessedTrainPool.pairsData
      ).repartition(workerCount)
      val pairsSchema = preprocessedTrainPool.pairsData.schema

      cogroupedTrainData.foreachPartition {
        groups : Iterator[(Long, (Iterable[Iterable[Row]], Iterable[Iterable[Row]]))] => {
          Worker.processPartition(
            trainingDriverListeningAddress,
            catBoostJsonParamsForWorkersString,
            quantizedFeaturesInfo,
            threadCount,
            () => {
              if (groups.hasNext) {
                DataHelpers.loadQuantizedDatasetWithPairs(
                  quantizedFeaturesInfo,
                  columnIndexMapForWorkers,
                  dataMetaInfo,
                  schemaForWorkers,
                  pairsSchema,
                  threadCount,
                  groups
                )
              } else {
                null
              }
           }
          )
        }
      }
    } else {
      trainDataForWorkers.repartition(workerCount).foreachPartition {
        rows : Iterator[Row] => {
          Worker.processPartition(
            trainingDriverListeningAddress,
            catBoostJsonParamsForWorkersString,
            quantizedFeaturesInfo,
            threadCount,
            () => {
              if (rows.hasNext) {
                DataHelpers.loadQuantizedDataset(
                  quantizedFeaturesInfo,
                  columnIndexMapForWorkers,
                  dataMetaInfo,
                  schemaForWorkers,
                  threadCount,
                  rows
                )
              } else {
                null
              }
            }
          )
        }
      }
    }
  }
}
