package com.johnsnowlabs.nlp.embeddings

import com.johnsnowlabs.nlp.AnnotatorType.{DOCUMENT, TOKEN, WORD_EMBEDDINGS}
import com.johnsnowlabs.nlp.{Annotation, AnnotatorModel, HasPretrained, ParamsAndFeaturesWritable}
import com.johnsnowlabs.nlp.annotators.common.{TokenPieceEmbeddings, TokenizedWithSentence, WordpieceEmbeddingsSentence}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.functions.{col, udf}
import org.apache.spark.sql.{DataFrame, Row}
import com.johnsnowlabs.nlp.util.io.ResourceHelper.spark.implicits._
import com.johnsnowlabs.storage.{Database, HasStorageModel, RocksDBConnection, StorageReadable}
import org.apache.spark.ml.PipelineModel
import org.apache.spark.ml.param.IntParam

class WordEmbeddingsModel(override val uid: String)
  extends AnnotatorModel[WordEmbeddingsModel]
    with HasEmbeddingsProperties
    with HasStorageModel
    with ParamsAndFeaturesWritable {

  def this() = this(Identifiable.randomUID("WORD_EMBEDDINGS_MODEL"))

  override val outputAnnotatorType: AnnotatorType = WORD_EMBEDDINGS
  /** Annotator reference id. Used to identify elements in metadata or to refer to this annotator type */
  override val inputAnnotatorTypes: Array[String] = Array(DOCUMENT, TOKEN)

  val readCacheSize = new IntParam(this, "readCacheSize", "cache size for items retrieved from storage. Increase for performance but higher memory consumption")
  def setReadCacheSize(value: Int): this.type = set(readCacheSize, value)

  /**
    * takes a document and annotations and produces new annotations of this annotator's annotation type
    *
    * @param annotations Annotations that correspond to inputAnnotationCols generated by previous annotators if any
    * @return any number of annotations processed for every input annotation. Not necessary one to one relationship
    */
  override def annotate(annotations: Seq[Annotation], recursivePipeline: Option[PipelineModel]): Seq[Annotation] = {
    val sentences = TokenizedWithSentence.unpack(annotations)
    val withEmbeddings = sentences.map{ s =>
      val tokens = s.indexedTokens.map { token =>
        val vectorOption = getReader(Database.EMBEDDINGS).lookup(token.token)
        TokenPieceEmbeddings(
          token.token,
          token.token,
          -1,
          isWordStart = true,
          vectorOption,
          getReader(Database.EMBEDDINGS).emptyValue,
          token.begin,
          token.end
        )
      }
      WordpieceEmbeddingsSentence(tokens, s.sentenceIndex)
    }

    WordpieceEmbeddingsSentence.pack(withEmbeddings)
  }

  override protected def afterAnnotate(dataset: DataFrame): DataFrame = {
    dataset.withColumn(getOutputCol, wrapEmbeddingsMetadata(dataset.col(getOutputCol), $(dimension), Some($(storageRef))))
  }

  private def bufferSizeFormula = {
    scala.math.min( // LRU Cache Size, pick the smallest value up to 50k to reduce memory blue print as dimension grows
      (100.0/$(dimension))*200000,
      50000
    ).toInt
  }

  override protected def createReader(database: Database.Name, connection: RocksDBConnection): WordEmbeddingsReader = {
    new WordEmbeddingsReader(
      connection,
      $(caseSensitive),
      $(dimension),
      get(readCacheSize).getOrElse(bufferSizeFormula)
      )
  }

  override val databases: Array[Database.Name] = Array(Database.EMBEDDINGS)
}

trait ReadablePretrainedWordEmbeddings extends StorageReadable[WordEmbeddingsModel] with HasPretrained[WordEmbeddingsModel] {
  override val defaultModelName: Option[String] = Some("glove_100d")
  /** Java compliant-overrides */
  override def pretrained(): WordEmbeddingsModel = super.pretrained()
  override def pretrained(name: String): WordEmbeddingsModel = super.pretrained(name)
  override def pretrained(name: String, lang: String): WordEmbeddingsModel = super.pretrained(name, lang)
  override def pretrained(name: String, lang: String, remoteLoc: String): WordEmbeddingsModel = super.pretrained(name, lang, remoteLoc)
}

trait EmbeddingsCoverage {

  case class CoverageResult(covered: Long, total: Long, percentage: Float)

  def withCoverageColumn(dataset: DataFrame, embeddingsCol: String, outputCol: String = "coverage"): DataFrame = {
    val coverageFn = udf((annotatorProperties: Seq[Row]) => {
      val annotations = annotatorProperties.map(Annotation(_))
      val oov = annotations.map(x => if (x.metadata.getOrElse("isOOV", "false") == "false") 1 else 0)
      val covered = oov.sum
      val total = annotations.count(_ => true)
      val percentage = 1f * covered / total
      CoverageResult(covered, total, percentage)
    })
    dataset.withColumn(outputCol, coverageFn(col(embeddingsCol)))
  }

  def overallCoverage(dataset: DataFrame, embeddingsCol: String): CoverageResult = {
    val words = dataset.select(embeddingsCol).flatMap(row => {
      val annotations = row.getAs[Seq[Row]](embeddingsCol)
      annotations.map(annotation => Tuple2(
        annotation.getAs[Map[String, String]]("metadata")("token"),
        if (annotation.getAs[Map[String, String]]("metadata").getOrElse("isOOV", "false") == "false") 1 else 0))
    })
    val oov = words.reduce((a, b) => Tuple2("Total", a._2 + b._2))
    val covered = oov._2
    val total = words.count()
    val percentage = 1f * covered / total
    CoverageResult(covered, total, percentage)
  }
}

object WordEmbeddingsModel extends ReadablePretrainedWordEmbeddings with EmbeddingsCoverage

