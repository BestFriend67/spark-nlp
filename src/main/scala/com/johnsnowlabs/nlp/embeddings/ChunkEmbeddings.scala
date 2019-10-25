package com.johnsnowlabs.nlp.embeddings

import com.johnsnowlabs.nlp.annotators.common.WordpieceEmbeddingsSentence
import com.johnsnowlabs.nlp.{Annotation, AnnotatorModel}
import org.apache.spark.ml.util.{DefaultParamsReadable, Identifiable}
import org.apache.spark.ml.param.Param

class ChunkEmbeddings (override val uid: String) extends AnnotatorModel[ChunkEmbeddings] {

  import com.johnsnowlabs.nlp.AnnotatorType._
  override val outputAnnotatorType: AnnotatorType = CHUNK_EMBEDDINGS

  override val inputAnnotatorTypes: Array[AnnotatorType] = Array(CHUNK, WORD_EMBEDDINGS)

  val poolingStrategy = new Param[String](this, "poolingStrategy",
    "Choose how you would like to aggregate Word Embeddings to Chunk Embeddings: AVERAGE or SUM")

  def setPoolingStrategy(strategy: String): this.type = {
    strategy.toLowerCase() match {
      case "average" => set(poolingStrategy, "AVERAGE")
      case "sum" => set(poolingStrategy, "SUM")
      case _ => throw new MatchError("poolingStrategy must be either AVERAGE or SUM")
    }
  }

  setDefault(
    inputCols -> Array(CHUNK, WORD_EMBEDDINGS),
    outputCol -> "chunk_embeddings",
    poolingStrategy -> "AVERAGE"
  )

  /** Internal constructor to submit a random UID */
  def this() = this(Identifiable.randomUID("CHUNK_EMBEDDINGS"))

  private def calculateChunkEmbeddings(matrix : Array[Array[Float]]):Array[Float] = {
    val res = Array.ofDim[Float](matrix(0).length)
    matrix(0).indices.foreach {
      j =>
        matrix.indices.foreach {
          i =>
            res(j) += matrix(i)(j)
        }
        if($(poolingStrategy) == "AVERAGE")
          res(j) /= matrix.length
    }
    res
  }

  /**
    * takes a document and annotations and produces new annotations of this annotator's annotation type
    *
    * @param annotations Annotations that correspond to inputAnnotationCols generated by previous annotators if any
    * @return any number of annotations processed for every input annotation. Not necessary one to one relationship
    */
  override def annotate(annotations: Seq[Annotation]): Seq[Annotation] = {

    val documentsWithChunks = annotations
      .filter(token => token.annotatorType == CHUNK)
      .groupBy(_.metadata.head._2.toInt)
      .toSeq
      .sortBy(_._1)

    val embeddingsSentences = WordpieceEmbeddingsSentence.unpack(annotations)

    documentsWithChunks.zipWithIndex.flatMap { case (sentences, idx) =>
      sentences._2.map { chunk =>

        val tokensWithEmbeddings = embeddingsSentences(idx).tokens.filter(
          token => token.begin == chunk.begin || token.end == chunk.end
        )

        val allEmbeddings = tokensWithEmbeddings.map {
          case (tokenEmbedding) =>
              val allEmbeddings = tokenEmbedding.embeddings
              allEmbeddings
        }

        Annotation(
          annotatorType = outputAnnotatorType,
          begin = chunk.begin,
          end = chunk.end,
          result = chunk.result,
          metadata = chunk.metadata,
          embeddings = calculateChunkEmbeddings(allEmbeddings)
        )

      }
    }
  }
}

object ChunkEmbeddings extends DefaultParamsReadable[ChunkEmbeddings]